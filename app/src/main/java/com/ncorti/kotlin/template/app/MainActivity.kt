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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

// --- ЛОКАЛЬНОЕ ХРАНИЛИЩЕ ---
class CollectionRepository(private val context: Context) {
    private val file = File(context.filesDir, "pokemon_collection.json")
    private val gson = Gson()

    fun load(): List<CollectedCard> {
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<CollectedCard>>() {}.type
            gson.fromJson(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(cards: List<CollectedCard>) {
        file.writeText(gson.toJson(cards))
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
    var collection by remember { mutableStateOf(repo.load()) }
    var selectedTab by remember { mutableIntStateOf(0) }

    fun updateCollection(newList: List<CollectedCard>) {
        collection = newList
        repo.save(newList)
    }

    fun addOrIncrementCard(card: PokemonCardDto) {
        val existing = collection.find { it.id == card.id }
        val updated = if (existing != null) {
            collection.map { if (it.id == card.id) it.copy(count = it.count + 1) else it }
        } else {
            listOf(
                CollectedCard(
                    id = card.id,
                    name = card.name,
                    number = card.number,
                    setName = card.set.name,
                    imageUrl = card.images.small,
                    count = 1
                )
            ) + collection
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
                ScannerScreen(onCardAdded = { addOrIncrementCard(it) })
            } else {
                CollectionScreen(
                    collection = collection,
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

// --- ЭКРАН 1: СКАНЕР С АВТОДОБАВЛЕНИЕМ ---
@Composable
fun ScannerScreen(onCardAdded: (PokemonCardDto) -> Unit) {
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

    var detectedText by remember { mutableStateOf("Наведите камеру на карту...") }
    var foundCard by remember { mutableStateOf<PokemonCardDto?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf("") }

    // Настройки автодобавления
    var autoAddEnabled by remember { mutableStateOf(true) }
    var lastAutoAddedId by remember { mutableStateOf("") }
    var lastAutoAddedTime by remember { mutableLongStateOf(0L) }
    var autoAddedBanner by remember { mutableStateOf<String?>(null) }

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
                        if (currentTime - lastAnalyzedTime < 1100) {
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
                                    val raw = visionText.text
                                    val parsed = parseCardQuery(raw)
                                    if (parsed != null && parsed != lastQuery && !isSearching) {
                                        lastQuery = parsed
                                        detectedText = "Поиск: $parsed"
                                        isSearching = true
                                        scope.launch {
                                            try {
                                                val res = withContext(Dispatchers.IO) {
                                                    ApiClient.api.searchCards(parsed)
                                                }
                                                val card = res.data.firstOrNull()
                                                foundCard = card
                                                if (card != null && autoAddEnabled) {
                                                    val now = System.currentTimeMillis()
                                                    // Защита от дублей: ту же карту повторно добавляем только через 2.5 сек
                                                    if (card.id != lastAutoAddedId || now - lastAutoAddedTime > 2500) {
                                                        lastAutoAddedId = card.id
                                                        lastAutoAddedTime = now
                                                        onCardAdded(card)
                                                        triggerVibration()
                                                        autoAddedBanner = "+1 ${card.name} (${card.set.name})"
                                                    }
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

        // Верхняя панель: статус + переключатель Автодобавления
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
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
            }

            // Всплывающее зеленое подтверждение автодобавления
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
                        Text("${card.set.name} • #${card.number}", fontSize = 13.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    onCardAdded(card)
                                    triggerVibration()
                                    autoAddedBanner = "+1 ${card.name}"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("+1 еще")
                            }
                            OutlinedButton(
                                onClick = {
                                    foundCard = null
                                    lastQuery = ""
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

fun parseCardQuery(rawText: String): String? {
    val lines = rawText.lines().map { it.trim() }.filter { it.length >= 3 }
    if (lines.isEmpty()) return null

    val numberRegex = Regex("""(\d{1,3})\s*/\s*\d{1,3}""")
    val numberMatch = numberRegex.find(rawText)
    val cardNumber = numberMatch?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }

    val ignoredWords = setOf("BASIC", "STAGE", "TRAINER", "ENERGY", "SUPPORTER", "ITEM", "VMAX", "VSTAR")
    val candidateName = lines.firstOrNull { line ->
        val clean = line.replace(Regex("""[^a-zA-Z\s-]"""), "").trim()
        clean.length in 3..20 && !ignoredWords.contains(clean.uppercase())
    }?.replace(Regex("""[^a-zA-Z\s-]"""), "")?.trim() ?: return null

    return if (cardNumber != null) {
        "name:\"$candidateName\" number:$cardNumber"
    } else {
        "name:\"$candidateName\""
    }
}

// --- ЭКРАН 2: КОЛЛЕКЦИЯ С ПОИСКОМ И ФИЛЬТРОМ ПО СЕТАМ ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    collection: List<CollectedCard>,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedSet by remember { mutableStateOf("Все сеты") }

    // Уникальные сеты из коллекции
    val availableSets = remember(collection) {
        listOf("Все сеты") + collection.map { it.setName }.distinct().sorted()
    }

    // Сбрасываем фильтр, если сет удален из коллекции
    LaunchedEffect(availableSets) {
        if (!availableSets.contains(selectedSet)) {
            selectedSet = "Все сеты"
        }
    }

    // Отфильтрованный список
    val filteredCollection = remember(collection, searchQuery, selectedSet) {
        collection.filter { card ->
            val matchesSet = selectedSet == "Все сеты" || card.setName == selectedSet
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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Шапка со счетчиками и экспортом
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

        Spacer(Modifier.height(10.dp))

        // Строка поиска
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по имени или номеру...") },
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

        // Горизонтальный фильтр по сетам
        if (availableSets.size > 1) {
            Spacer(Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(availableSets) { setName ->
                    val countInSet = if (setName == "Все сеты") {
                        collection.sumOf { it.count }
                    } else {
                        collection.filter { it.setName == setName }.sumOf { it.count }
                    }
                    FilterChip(
                        selected = selectedSet == setName,
                        onClick = { selectedSet = setName },
                        label = { Text("$setName ($countInSet)") }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (collection.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Коллекция пуста. Отсканируй первую карту!", color = Color.Gray)
            }
        } else if (filteredCollection.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("По этому фильтру карт не найдено", color = Color.Gray)
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
                            Text("${card.setName} #${card.number}", fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
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
