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

// --- API POKÉMON TCG ---
interface PokemonTcgApi {
    @GET("v2/cards")
    suspend fun searchCards(
        @Query("q") query: String,
        @Query("pageSize") pageSize: Int = 5
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
    var activeScanFolder by remember { mutableStateOf<String?>(null) } // null = по сету карты
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
        // Уникальный ключ учитывает папку, чтобы одна карта могла лежать в разных папках
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
    val apiQuery: String,
    val displayLabel: String,
    val hasExactNumber: Boolean
)

// --- ЭКРАН 1: ТОЧНЫЙ СКАНЕР С ВЫБОРОМ ПАПКИ ---
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

    fun triggerVibration() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                v.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(autoAddedBanner) {
        if (autoAddedBanner != null) {
            delay(2000)
            autoAddedBanner = null
        }
    }

    if (showNewFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { folderName ->
                onCreateFolder(folderName)
                onSelectScanFolder(folderName.trim())
                showNewFolderDialog = false
            }
        )
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Разрешить доступ к камере")
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val executor = Executors.newSingleThreadExecutor()
                    var lastAnalyzedTime = 0L

                    analyzer.setAnalyzer(executor) { imageProxy: ImageProxy ->
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastAnalyzedTime < 1300) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        @androidx.camera.core.ExperimentalGetImage
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            lastAnalyzedTime = currentTime
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    val parsed = parseCardSmart(visionText)
                                    val now = System.currentTimeMillis()
                                    if (parsed != null && !isSearching && (parsed.apiQuery != lastQuery || now - lastQueryTime > 4000)) {
                                        lastQuery = parsed.apiQuery
                                        lastQueryTime = now
                                        detectedText = "Ищу: ${parsed.displayLabel}"
                                        isSearching = true
                                        scope.launch {
                                            try {
                                                val res = withContext(Dispatchers.IO) {
                                                    ApiClient.api.searchCards(parsed.apiQuery)
                                                }
                                                val card = res.data.firstOrNull()
                                                if (card != null) {
                                                    foundCard = card
                                                    detectedText = "Найдено: ${card.name} (#${card.number})"
                                                    if (autoAddState && parsed.hasExactNumber) {
                                                        val addNow = System.currentTimeMillis()
                                                        if (card.id != lastAutoAddedId || addNow - lastAutoAddedTime > 3000) {
                                                            lastAutoAddedId = card.id
                                                            lastAutoAddedTime = addNow
                                                            onCardAdded(card)
                                                            triggerVibration()
                                                            val dest = currentFolderState ?: card.set.name
                                                            autoAddedBanner = "+1 ${card.name} → $dest"
                                                        }
                                                    }
                                                } else {
                                                    detectedText = "Не найдено: ${parsed.displayLabel}"
                                                }
                                            } catch (e: Exception) {
                                                Log.e("PokeScanner", "API Error", e)
                                            } finally {
                                                isSearching = false
                                            }
                                        }
                                    }
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analyzer
                        )
                    } catch (e: Exception) {
                        Log.e("PokeScanner", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Верхняя панель: статус + авто + выбор целевой папки
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.80f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = detectedText,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (autoAddEnabled) "Авто: ВКЛ" else "Авто: ВЫКЛ",
                                color = if (autoAddEnabled) Color(0xFF4CAF50) else Color.LightGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = autoAddEnabled,
                                onCheckedChange = { autoAddEnabled = it }
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Быстрый выбор папки сохранения прямо в сканере
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = activeScanFolder == null,
                                onClick = { onSelectScanFolder(null) },
                                label = { Text("Родной сет", fontSize = 11.sp) }
                            )
                        }
                        items(customFolders) { folder ->
                            FilterChip(
                                selected = activeScanFolder == folder,
                                onClick = { onSelectScanFolder(folder) },
                                leadingIcon = {
                                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                label = { Text(folder, fontSize = 11.sp) }
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { showNewFolderDialog = true },
                                leadingIcon = {
                                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                                label = { Text("+ Папка", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            autoAddedBanner?.let { bannerText ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Color(0xFF2E7D32),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(bannerText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Карточка найденного покемона снизу
        foundCard?.let { card ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = card.images.small,
                        contentDescription = card.name,
                        modifier = Modifier.size(72.dp, 100.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = "В папку: ${activeScanFolder ?: card.set.name} • #${card.number}",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onCardAdded(card)
                                    triggerVibration()
                                    val dest = activeScanFolder ?: card.set.name
                                    autoAddedBanner = "+1 ${card.name} → $dest"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("+1 в папку")
                            }
                            OutlinedButton(
                                onClick = {
                                    foundCard = null
                                    lastQuery = ""
                                    detectedText = "Наведите камеру на карту..."
                                }
                            ) {
                                Text("Сброс")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun parseCardSmart(visionText: Text): ParsedCardInfo? {
    val rawText = visionText.text
    if (rawText.isBlank()) return null

    val numberRegex = Regex("""\b([A-Za-z]{0,3}\d{1,3})\s*/\s*[A-Za-z]{0,3}\d{1,3}\b""")
    val numberMatch = numberRegex.find(rawText)
    val rawNum = numberMatch?.groupValues?.get(1)
    val cleanNumber = rawNum?.let { num ->
        if (num.all { it.isDigit() }) num.trimStart('0').ifEmpty { "0" } else num
    }

    val sortedLines = visionText.textBlocks
        .sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
        .flatMap { it.lines }
        .map { it.text.trim() }

    val serviceWords = setOf(
        "BASIC", "STAGE 1", "STAGE 2", "VMAX", "VSTAR", "TRAINER",
        "SUPPORTER", "ITEM", "STADIUM", "ENERGY", "SPECIAL ENERGY", "EVOLVES FROM"
    )

    val candidateName = sortedLines.mapNotNull { line ->
        val withoutHp = line
            .replace(Regex("""\bHP\s*\d+\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d+\s*HP\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^a-zA-Z0-9\s.'-]"""), "")
            .trim()

        val upper = withoutHp.uppercase()
        if (withoutHp.length in 3..28 &&
            !serviceWords.any { upper == it || upper.startsWith("EVOLVES FROM") } &&
            withoutHp.any { it.isLetter() }
        ) {
            withoutHp
        } else {
            null
        }
    }.firstOrNull() ?: return null

    return if (cleanNumber != null) {
        ParsedCardInfo(
            apiQuery = "name:\"$candidateName\" number:$cleanNumber",
            displayLabel = "$candidateName #$cleanNumber",
            hasExactNumber = true
        )
    } else {
        ParsedCardInfo(
            apiQuery = "name:\"$candidateName\"",
            displayLabel = candidateName,
            hasExactNumber = false
        )
    }
}

// --- ДИАЛОГ СОЗДАНИЯ ПАПКИ ---
@Composable
fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая папка (сет)") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                placeholder = { Text("Например: На обмен, 151, Редкие...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(folderName) },
                enabled = folderName.trim().isNotEmpty()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

// --- ЭКРАН 2: КОЛЛЕКЦИЯ С ПОЛЬЗОВАТЕЛЬСКИМИ ПАПКАМИ ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    collection: List<CollectedCard>,
    customFolders: List<String>,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onMoveCard: (String, String) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedSet by remember { mutableStateOf("Все") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var cardToMove by remember { mutableStateOf<CollectedCard?>(null) }

    // Объединяем пользовательские папки и сеты из отсканированных карт
    val availableSets = remember(collection, customFolders) {
        val allNames = (customFolders + collection.map { it.setName }).distinct().sorted()
        listOf("Все") + allNames
    }

    LaunchedEffect(availableSets) {
        if (!availableSets.contains(selectedSet)) {
            selectedSet = "Все"
        }
    }

    val filteredCollection = remember(collection, searchQuery, selectedSet) {
        collection.filter { card ->
            val matchesSet = selectedSet == "Все" || card.setName == selectedSet
            val q = searchQuery.trim().lowercase()
            val matchesSearch = q.isEmpty() ||
                card.name.lowercase().contains(q) ||
                card.number.lowercase().contains(q) ||
                card.setName.lowercase().contains(q)
            matchesSet && matchesSearch
        }
    }

    val totalCount = filteredCollection.sumOf { it.count }
    val uniqueCount = filteredCollection.size

    if (showNewFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                onCreateFolder(name)
                selectedSet = name.trim()
                showNewFolderDialog = false
            }
        )
    }

    // Диалог перемещения карты в другую папку
    cardToMove?.let { card ->
        val targetFolders = (customFolders + collection.map { it.setName }).distinct().sorted()
        AlertDialog(
            onDismissRequest = { cardToMove = null },
            title = { Text("Переместить «${card.name}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (targetFolders.isEmpty()) {
                        Text("Сначала создай папку кнопкой «+ Папка».")
                    } else {
                        targetFolders.forEach { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onMoveCard(card.id, folder)
                                        cardToMove = null
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (folder == card.setName) Icons.Default.FolderOpen else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (folder == card.setName) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = folder,
                                    fontWeight = if (folder == card.setName) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    cardToMove = null
                    showNewFolderDialog = true
                }) {
                    Text("+ Новая папка")
                }
            },
            dismissButton = {
                TextButton(onClick = { cardToMove = null }) {
                    Text("Закрыть")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Моя коллекция", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Показано: $totalCount карт ($uniqueCount уник.)",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
            Row {
                IconButton(onClick = { showNewFolderDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Создать папку")
                }
                IconButton(
                    onClick = {
                        val exportText = filteredCollection.joinToString("\n") {
                            "${it.count}x ${it.name} (${it.setName} #${it.number})"
                        }
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "Моя коллекция Pokémon TCG:\n\n$exportText")
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Экспорт списка"))
                    },
                    enabled = filteredCollection.isNotEmpty()
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Экспорт")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по имени, номеру или папке...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))

        // Лента папок и сетов + кнопка создания новой папки
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            items(availableSets) { setName ->
                val countInSet = if (setName == "Все") {
                    collection.sumOf { it.count }
                } else {
                    collection.filter { it.setName == setName }.sumOf { it.count }
                }
                val isCustom = customFolders.contains(setName)
                FilterChip(
                    selected = selectedSet == setName,
                    onClick = { selectedSet = setName },
                    leadingIcon = if (isCustom) {
                        { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    trailingIcon = if (isCustom && countInSet == 0) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Удалить пустую папку",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onDeleteFolder(setName) }
                            )
                        }
                    } else null,
                    label = { Text("$setName ($countInSet)") }
                )
            }
            item {
                AssistChip(
                    onClick = { showNewFolderDialog = true },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Папка") }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (collection.isEmpty() && selectedSet == "Все") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Коллекция пуста. Отсканируй первую карту!", color = Color.Gray)
            }
        } else if (filteredCollection.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (selectedSet != "Все") "В папке «$selectedSet» пока пусто" else "По этому фильтру карт не найдено",
                    color = Color.Gray
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredCollection, key = { it.id }) { card ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Box {
                                AsyncImage(
                                    model = card.imageUrl,
                                    contentDescription = card.name,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "x${card.count}",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(card.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { cardToMove = card }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${card.setName} #${card.number}",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalIconButton(
                                    onClick = { onDecrement(card.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "Убрать", modifier = Modifier.size(16.dp))
                                }
                                Text("${card.count}", fontWeight = FontWeight.Bold)
                                FilledTonalIconButton(
                                    onClick = { onIncrement(card.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Добавить", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
