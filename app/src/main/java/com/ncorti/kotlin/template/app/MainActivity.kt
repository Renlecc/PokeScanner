package com.ncorti.kotlin.template.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
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

data class ApiResponse(val data: List<PokemonCardDto>)
data class PokemonCardDto(val id: String, val name: String, val number: String, val set: CardSetDto, val images: CardImagesDto)
data class CardSetDto(val name: String)
data class CardImagesDto(val small: String, val large: String)
data class CollectedCard(val id: String, val name: String, val number: String, val setName: String, val imageUrl: String, val count: Int)
data class ParsedCardInfo(val primaryQuery: String, val fallbackQuery: String?, val displayLabel: String, val hasExactNumber: Boolean)

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
        Retrofit.Builder().baseUrl("https://api.pokemontcg.io/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(PokemonTcgApi::class.java)
    }
    val cache = ConcurrentHashMap<String, PokemonCardDto>()
}

class CollectionRepository(context: Context) {
    private val cardsFile = File(context.filesDir, "pokemon_collection.json")
    private val foldersFile = File(context.filesDir, "custom_folders.json")
    private val gson = Gson()

    fun loadCards(): List<CollectedCard> = try {
        if (!cardsFile.exists()) emptyList()
        else gson.fromJson(cardsFile.readText(), object : TypeToken<List<CollectedCard>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun saveCards(cards: List<CollectedCard>) = cardsFile.writeText(gson.toJson(cards))

    fun loadFolders(): List<String> = try {
        if (!foldersFile.exists()) emptyList()
        else gson.fromJson(foldersFile.readText(), object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun saveFolders(folders: List<String>) = foldersFile.writeText(gson.toJson(folders))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PokeCollectorApp() } }
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

    fun updateCollection(newList: List<CollectedCard>) { collection = newList; repo.saveCards(newList) }
    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty() && !customFolders.contains(clean)) {
            customFolders = customFolders + clean; repo.saveFolders(customFolders)
        }
    }
    fun deleteFolder(name: String) {
        customFolders = customFolders.filter { it != name }; repo.saveFolders(customFolders)
        if (activeScanFolder == name) activeScanFolder = null
    }
    fun addOrIncrementCard(card: PokemonCardDto, targetFolder: String?) {
        val resolvedSet = targetFolder ?: card.set.name
        val entryId = "${card.id}::$resolvedSet"
        val existing = collection.find { it.id == entryId }
        updateCollection(if (existing != null) collection.map { if (it.id == entryId) it.copy(count = it.count + 1) else it }
        else listOf(CollectedCard(entryId, card.name, card.number, resolvedSet, card.images.small, 1)) + collection)
    }
    fun moveCardToFolder(cardId: String, newFolder: String) {
        val target = collection.find { it.id == cardId } ?: return
        if (target.setName == newFolder) return
        val newId = "${target.id.substringBefore("::")}::$newFolder"
        val existing = collection.find { it.id == newId }
        updateCollection(if (existing != null) collection.filter { it.id != cardId }.map { if (it.id == newId) it.copy(count = it.count + target.count) else it }
        else collection.map { if (it.id == cardId) it.copy(id = newId, setName = newFolder) else it })
    }

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.QrCodeScanner, null) }, label = { Text("Сканер ∞") })
            NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.CollectionsBookmark, null) }, label = { Text("Коллекция (${collection.sumOf { it.count }})") })
        }
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (selectedTab == 0) {
                ScannerScreen(customFolders, activeScanFolder, { activeScanFolder = it }, { createFolder(it) }, { addOrIncrementCard(it, activeScanFolder) })
            } else {
                CollectionScreen(collection, customFolders, { createFolder(it) }, { deleteFolder(it) }, { id, f -> moveCardToFolder(id, f) },
                    { id -> updateCollection(collection.map { if (it.id == id) it.copy(count = it.count + 1) else it }) },
                    { id -> updateCollection(collection.mapNotNull { if (it.id != id) it else if (it.count > 1) it.copy(count = it.count - 1) else null }) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    customFolders: List<String>, activeScanFolder: String?,
    onSelectScanFolder: (String?) -> Unit, onCreateFolder: (String) -> Unit, onCardAdded: (PokemonCardDto) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var detectedText by remember { mutableStateOf("Наведите камеру на карту...") }
    var foundCard by remember { mutableStateOf<PokemonCardDto?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf("") }
    var lastQueryTime by remember { mutableLongStateOf(0L) }
    var autoAddEnabled by remember { mutableStateOf(true) }
    var lastAutoAddedId by remember { mutableStateOf("") }
    var lastAutoAddedTime by remember { mutableLongStateOf(0L) }
    var autoAddedBanner by remember { mutableStateOf<String?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    val currentFolderState by rememberUpdatedState(activeScanFolder)
    val autoAddState by rememberUpdatedState(autoAddEnabled)

    fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(60)
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(autoAddedBanner) { if (autoAddedBanner != null) { delay(1600); autoAddedBanner = null } }
    if (showNewFolderDialog) {
        CreateFolderDialog({ showNewFolderDialog = false }) { name -> onCreateFolder(name); onSelectScanFolder(name.trim()); showNewFolderDialog = false }
    }
    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") } }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            val previewView = PreviewView(ctx).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
            ProcessCameraProvider.getInstance(ctx).addListener({
                val provider = ProcessCameraProvider.getInstance(ctx).get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                var lastAnalyzed = 0L

                analyzer.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastAnalyzed < 450) { proxy.close(); return@setAnalyzer }
                    @androidx.camera.core.ExperimentalGetImage
                    val media = proxy.image
                    if (media != null) {
                        lastAnalyzed = now
                        recognizer.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { vision ->
                                val parsed = parseCardClean(vision)
                                val t = System.currentTimeMillis()
                                if (parsed != null && !isSearching && (parsed.primaryQuery != lastQuery || t - lastQueryTime > 2000)) {
                                    lastQuery = parsed.primaryQuery; lastQueryTime = t; detectedText = "Ищу: ${parsed.displayLabel}"; isSearching = true
                                    scope.launch {
                                        try {
                                            val card = ApiClient.cache[parsed.primaryQuery] ?: withContext(Dispatchers.IO) {
                                                val res = ApiClient.api.searchCards(parsed.primaryQuery).data.firstOrNull()
                                                    ?: parsed.fallbackQuery?.let { ApiClient.api.searchCards(it).data.firstOrNull() }
                                                if (res != null) ApiClient.cache[parsed.primaryQuery] = res
                                                res
                                            }
                                            if (card != null) {
                                                foundCard = card; detectedText = "Найдено: ${card.name} (#${card.number})"
                                                if (autoAddState && parsed.hasExactNumber) {
                                                    val addT = System.currentTimeMillis()
                                                    if (card.id != lastAutoAddedId || addT - lastAutoAddedTime > 1500) {
                                                        lastAutoAddedId = card.id; lastAutoAddedTime = addT; onCardAdded(card); vibrate()
                                                        autoAddedBanner = "+1 ${card.name} →${currentFolderState ?: card.set.name}"
                                                    }
                                                }
                                            } else detectedText = "Не найдено: ${parsed.displayLabel}"
                                        } catch (_: Exception) {} finally { isSearching = false }
                                    }
                                }
                            }.addOnCompleteListener { proxy.close() }
                    } else proxy.close()
                }
                try { provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer) } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(detectedText, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (autoAddEnabled) "Авто: ВКЛ" else "Авто: ВЫКЛ", color = if (autoAddEnabled) Color(0xFF4CAF50) else Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Switch(checked = autoAddEnabled, onCheckedChange = { autoAddEnabled = it })
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = activeScanFolder == null, onClick = { onSelectScanFolder(null) }, label = { Text("Родной сет", fontSize = 11.sp) }) }
                        items(customFolders) { f -> FilterChip(selected = activeScanFolder == f, onClick = { onSelectScanFolder(f) }, label = { Text(f, fontSize = 11.sp) }) }
                        item { AssistChip(onClick = { showNewFolderDialog = true }, label = { Text("+ Папка", fontSize = 11.sp) }) }
                    }
                }
            }
            autoAddedBanner?.let {
                Spacer(Modifier.height(8.dp))
                Surface(color = Color(0xFF2E7D32), shape = RoundedCornerShape(20.dp)) {
                    Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }

        foundCard?.let { card ->
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = card.images.small, contentDescription = card.name, modifier = Modifier.size(72.dp, 100.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("В папку: ${activeScanFolder ?: card.set.name} • #${card.number}", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onCardAdded(card); vibrate(); autoAddedBanner = "+1 ${card.name} →${activeScanFolder ?: card.set.name}" }, modifier = Modifier.weight(1f)) { Text("+1 в папку") }
                            OutlinedButton(onClick = { foundCard = null; lastQuery = ""; detectedText = "Наведите камеру..." }) { Text("Сброс") }
                        }
                    }
                }
            }
        }
    }
}

fun parseCardClean(visionText: Text): ParsedCardInfo? {
    val rawText = visionText.text
    if (rawText.isBlank()) return null
    val numMatch = Regex("""\b([A-Za-z]{0,3}\d{1,3})\s*/\s*[A-Za-z]{0,3}\d{1,3}\b""").find(rawText)
    val rawNum = numMatch?.groupValues?.get(1)
    val cleanNumber = rawNum?.let { if (it.all { c -> c.isDigit() }) it.trimStart('0').ifEmpty { "0" } else it }

    val allLines = visionText.textBlocks.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
    val badPrefixes = Regex("""^(STAGE\s
