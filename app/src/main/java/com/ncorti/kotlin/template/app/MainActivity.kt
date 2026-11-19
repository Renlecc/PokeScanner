package com.ncorti.kotlin.template.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
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
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.Executors

// ==========================================
// 1. МОДЕЛЬ КАРТЫ И ЛОКАЛЬНОЕ ХРАНИЛИЩЕ
// ==========================================

data class CollectedCard(
    val id: String,
    val name: String,
    val setName: String,
    val number: String,
    val imageUrl: String,
    val count: Int
)

class CollectionStorage(context: Context) {
    private val file = File(context.filesDir, "pokemon_collection.json")
    private val gson = Gson()

    fun loadCards(): List<CollectedCard> {
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<CollectedCard>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCards(cards: List<CollectedCard>) {
        try {
            file.writeText(gson.toJson(cards))
        } catch (e: Exception) {
            Log.e("STORAGE", "Ошибка сохранения", e)
        }
    }
}

// ==========================================
// 2. POKEMON TCG API (БЕСПЛАТНАЯ БАЗА КАРТ)
// ==========================================

data class ApiCardResponse(val data: List<ApiCard>)
data class ApiCard(
    val id: String,
    val name: String,
    val number: String,
    val set: ApiSet,
    val images: ApiImages
)
data class ApiSet(val name: String)
data class ApiImages(val small: String, val large: String)

interface PokemonApi {
    @GET("v2/cards")
    suspend fun searchCards(@Query("q") query: String): ApiCardResponse

    companion object {
        fun create(): PokemonApi {
            return Retrofit.Builder()
                .baseUrl("https://api.pokemontcg.io/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PokemonApi::class.java)
        }
    }
}

// ==========================================
// 3. АНАЛИЗАТОР КАМЕРЫ (ML KIT БЕЗ ЛИМИТОВ)
// ==========================================

class CardAnalyzer(
    private val onMatchFound: (name: String, number: String) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val numberRegex = Regex("""(\d{1,3})\s*/\s*(\d{1,3})""")
    private var lastScanTime = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScanTime < 1200) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val lines = visionText.textBlocks.flatMap { it.lines }.map { it.text.trim() }
                val numberMatch = lines.firstNotNullOfOrNull { numberRegex.find(it) }
                val nameCandidate = lines.firstOrNull { line ->
                    line.length in 3..24 &&
                    !line.contains("HP", ignoreCase = true) &&
                    !line.contains("BASIC", ignoreCase = true) &&
                    !line.contains("STAGE", ignoreCase = true) &&
                    line.all { it.isLetter() || it == ' ' || it == '-' || it == '\'' }
                }

                if (numberMatch != null && nameCandidate != null) {
                    lastScanTime = System.currentTimeMillis()
                    val cleanNumber = numberMatch.groupValues[1].trimStart('0').ifEmpty { "0" }
                    onMatchFound(nameCandidate, cleanNumber)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}

// ==========================================
// 4. ГЛАВНЫЙ ЭКРАН И ИНТЕРФЕЙС (COLLECTR STYLE)
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = CollectionStorage(this)
        val api = PokemonApi.create()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PokeCollectorApp(storage, api)
            }
        }
    }
}

@Composable
fun PokeCollectorApp(storage: CollectionStorage, api: PokemonApi) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf(storage.loadCards()) }
    val totalCardsCount = cards.sumOf { it.count }

    fun updateCards(newList: List<CollectedCard>) {
        val sorted = newList.filter { it.count > 0 }.sortedBy { it.name }
        cards = sorted
        storage.saveCards(sorted)
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF16181D)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
                    label = { Text("Коллекция ($totalCardsCount)") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    label = { Text("Сканер ∞") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF0F1115))
        ) {
            if (selectedTab == 0) {
                CollectionScreen(
                    cards = cards,
                    onIncrement = { cardId ->
                        updateCards(cards.map { if (it.id == cardId) it.copy(count = it.count + 1) else it })
                    },
                    onDecrement = { cardId ->
                        updateCards(cards.map { if (it.id == cardId) it.copy(count = it.count - 1) else it })
                    }
                )
            } else {
                ScannerScreen(
                    api = api,
                    onCardAdded = { apiCard ->
                        val existing = cards.find { it.id == apiCard.id }
                        if (existing != null) {
                            updateCards(cards.map { if (it.id == apiCard.id) it.copy(count = it.count + 1) else it })
                        } else {
                            updateCards(
                                cards + CollectedCard(
                                    id = apiCard.id,
                                    name = apiCard.name,
                                    setName = apiCard.set.name,
                                    number = apiCard.number,
                                    imageUrl = apiCard.images.small,
                                    count = 1
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun CollectionScreen(
    cards: List<CollectedCard>,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit
) {
    val context = LocalContext.current
    val totalCount = cards.sumOf { it.count }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Моя коллекция", color = Color.Gray, fontSize = 14.sp)
                Text(
                    "$totalCount карт",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Уникальных: ${cards.size}",
                    color = Color(0xFF4ADE80),
                    fontSize = 13.sp
                )
            }
            IconButton(onClick = {
                val exportText = cards.joinToString("\n") { "${it.count}x ${it.name} (${it.setName} #${it.number})" }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, exportText)
                }
                context.startActivity(Intent.createChooser(intent, "Экспорт коллекции"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Экспорт", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (cards.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Коллекция пуста. Перейди во вкладку «Сканер ∞»", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(cards, key = { it.id }) { card ->
                    CardItemView(
                        card = card,
                        onAdd = { onIncrement(card.id) },
                        onRemove = { onDecrement(card.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun CardItemView(card: CollectedCard, onAdd: () -> Unit, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1F26))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.72f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xFF2563EB), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        "x${card.count}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                card.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${card.setName} #${card.number}",
                color = Color.Gray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "-", tint = Color.White)
                }
                Text("${card.count} шт.", color = Color.White, fontSize = 13.sp)
                IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "+", tint = Color(0xFF4ADE80))
                }
            }
        }
    }
}

@Composable
fun ScannerScreen(api: PokemonApi, onCardAdded: (ApiCard) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    var detectedCards by remember { mutableStateOf<List<ApiCard>>(emptyList()) }
    var statusText by remember { mutableStateOf("Наведи камеру на карту Pokémon...") }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                Text("Разрешить доступ к камере")
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(Executors.newSingleThreadExecutor(), CardAnalyzer { name, number ->
                                scope.launch {
                                    statusText = "Ищу: $name #$number..."
                                    try {
                                        val res = api.searchCards("name:\"$name\" number:$number")
                                        if (res.data.isNotEmpty()) {
                                            detectedCards = res.data
                                            statusText = "Найдено: ${res.data.first().name}"
                                        }
                                    } catch (e: Exception) {
                                        Log.e("SCANNER", "Ошибка API", e)
                                    }
                                }
                            })
                        }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analyzer
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(statusText, color = Color.White, fontSize = 14.sp)
        }

        if (detectedCards.isNotEmpty()) {
            val topMatch = detectedCards.first()
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1F26))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = topMatch.images.small,
                        contentDescription = null,
                        modifier = Modifier.size(60.dp, 84.dp).clip(RoundedCornerShape(6.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(topMatch.name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${topMatch.set.name} (#${topMatch.number})", color = Color.Gray, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            onCardAdded(topMatch)
                            Toast.makeText(context, "+1 ${topMatch.name}", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text("+1 в базу")
                    }
                }
            }
        }
    }
}
