package com.ncorti.kotlin.template.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// --- МОДЕЛИ ДАННЫХ ---
data class ApiResponse(val data: List<PokemonCardDto>)
data class PokemonCardDto(
    val id: String,
    val name: String,
    val number: String,
    val set: CardSetDto,
    val images: CardImagesDto
)
data class CardSetDto(val name: String)
data class CardImagesDto(val small: String, val large: String)

data class CollectedCard(
    val id: String,
    val name: String,
    val number: String,
    val setName: String,
    val imageUrl: String,
    val count: Int
)

// --- ОПТИМИЗИРОВАННЫЙ API POKÉMON TCG ---
interface PokemonTcgApi {
    @GET("v2/cards")
    suspend fun searchCards(
        @Query("q") query: String,
        @Query("pageSize") pageSize: Int = 1,
        @Query("select") select: String = "id,name,number,set,images"
    ): ApiResponse
}

object ApiClient {
    val api: PokemonTcgApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.pokemontcg.io/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PokemonTcgApi::class.java)
    }

    // Локальный кэш для мгновенного повторного считывания
    val cache = ConcurrentHashMap<String, PokemonCardDto>()
}

// --- ЛОКАЛЬНОЕ ХРАНИЛИЩЕ КАРТ И ПОЛЬЗОВАТЕЛЬСКИХ ПАПОК ---
class CollectionRepository(private val context: Context) {
    private val cardsFile = File(context.filesDir, "pokemon_collection.json")
    private val foldersFile = File(context.filesDir, "custom_folders.json")
    private val gson = Gson()

    fun loadCards(): List<CollectedCard> {
        if (!cardsFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<CollectedCard>>() {}.type
            gson.fromJson(cardsFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCards(cards: List<CollectedCard>) {
        cardsFile.writeText(gson.toJson(cards))
    }

    fun loadFolders(): List<String> {
        if (!foldersFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(foldersFile.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFolders(folders: List<String>) {
        foldersFile.writeText(gson.toJson(folders))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PokeCollectorApp()
            }
        }
    }
}

@Composable
fun PokeCollectorApp() {
    val context = LocalContext.current
    val repo = remember { CollectionRepository(context) }
    var collection by remember { mutableStateOf(repo.loadCards()) }
    var customFolders by remember { mutableStateOf(repo.loadFolders()) }
    var activeScanFolder by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    fun updateCollection(newList: List<CollectedCard>) {
        collection = newList
        repo.saveCards(newList)
    }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty() && !customFolders.contains(clean)) {
            val updated = customFolders + clean
            customFolders = updated
            repo.saveFolders(updated)
        }
    }

    fun deleteFolder(name: String) {
        val updated = customFolders.filter { it != name }
        customFolders = updated
        repo.saveFolders(updated)
        if (activeScanFolder == name) {
            activeScanFolder = null
        }
    }

    fun addOrIncrementCard(card: PokemonCardDto, targetFolder: String?) {
        val resolvedSet = targetFolder ?: card.set.name
        val entryId = "${card.id}::$resolvedSet"
        val existing = collection.find { it.id == entryId }
        val updated = if (existing != null) {
            collection.map { if (it.id == entryId) it.copy(count = it.count + 1) else it }
        } else {
            listOf(
                CollectedCard(
                    id = entryId,
                    name = card.name,
                    number = card.number,
                    setName = resolvedSet,
                    imageUrl = card.images.small,
                    count = 1
                )
            ) + collection
        }
        updateCollection(updated)
    }

    fun moveCardToFolder(cardId: String, newFolder: String) {
        val targetCard = collection.find { it.id == cardId } ?: return
        if (targetCard.setName == newFolder) return

        val baseApiId = targetCard.id.substringBefore("::")
        val newEntryId = "$baseApiId::$newFolder"
        val existingInTarget = collection.find { it.id == newEntryId }

        val updated = if (existingInTarget != null) {
            collection
                .filter { it.id != cardId }
                .map { if (it.id == newEntryId) it.copy(count = it.count + targetCard.count) else it }
        } else {
            collection.map {
                if (it.id == cardId) it.copy(id = newEntryId, setName = newFolder) else it
            }
        }
        updateCollection(updated)
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = "Сканер") },
                    label = { Text("Сканер ∞") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = "Коллекция") },
                    label = { Text("Коллекция (${collection.sumOf { it.count }})") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedTab == 0) {
                ScannerScreen(
                    customFolders = customFolders,
                    activeScanFolder = activeScanFolder,
                    onSelectScanFolder = { activeScanFolder = it },
                    onCreateFolder = { createFolder(it) },
                    onCardAdded = { card -> addOrIncrementCard(card, activeScanFolder) }
                )
            } else {
                CollectionScreen(
                    collection = collection,
                    customFolders = customFolders,
                    onCreateFolder = { createFolder(it) },
                    onDeleteFolder = { deleteFolder(it) },
                    onMoveCard = { cardId, folder -> moveCardToFolder(cardId, folder) },
                    onIncrement = { id ->
                        updateCollection(collection.map { if (it.id == id) it.copy(count = it.count + 1) else it })
                    },
                    onDecrement = { id ->
                        updateCollection(
                            collection.mapNotNull {
                                when {
                                    it.id != id -> it
                                    it.count > 1 -> it.copy(count = it.count - 1)
                                    else -> null
                                }
                            }
                        )
                    }
                )
            }
        }
    }
}

data class ParsedCardInfo(
    val primaryQuery: String,
    val fallbackQuery: String?,
    val displayLabel: String,
    val hasExactNumber: Boolean
)

// --- ЭКРАН 1: БЫСТРЫЙ СКАНЕР ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    customFolders: List<String>,
    activeScanFolder: String?,
    onSelectScanFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCardAdded: (PokemonCardDto) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    var detectedText by remember { mutableStateOf("Наведите камеру на карту целиком...") }
    var foundCard by remember { mutableStateOf<PokemonCardDto?>(null) }
    var isSearching by remember { mutable
