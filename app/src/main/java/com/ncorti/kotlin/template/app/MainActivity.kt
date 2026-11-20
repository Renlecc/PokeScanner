package com.ncorti.kotlin.template.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioManager
import android.media.Image
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// --- Data Models ---
data class TcgSet(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val releaseDate: String?,
    val totalCards: Int
)

data class CardSlot(
    val slotId: String,       // e.g. "sv03.5-001::Normal"
    val cardId: String,       // e.g. "sv03.5-001"
    val setId: String,
    val setName: String,
    val name: String,
    val localId: String,      // e.g. "001"
    val setTotal: Int,
    val rarity: String,
    val finish: String,       // "Normal", "Reverse Holofoil", "Holofoil"
    val imageUrl: String
)

data class CardHashEntry(
    val cardId: String,
    val setId: String,
    val dHash: Long,
    val colorSig: IntArray
)

object NetClient {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
}

// --- TCGdex API Service ---
object TcgDexRepository {
    suspend fun fetchAllSets(): List<TcgSet> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.tcgdex.net/v2/en/sets").build()
        NetClient.okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: return@withContext emptyList()
            val arr = JsonParser.parseString(body).asJsonArray
            val list = mutableListOf<TcgSet>()
            for (el in arr) {
                val obj = el.asJsonObject
                val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val logoRaw = obj.get("logo")?.takeIf { !it.isJsonNull }?.asString
                val logoUrl = logoRaw?.let { "$it.webp" }
                val releaseDate = obj.get("releaseDate")?.takeIf { !it.isJsonNull }?.asString
                val cardCountObj = obj.getAsJsonObject("cardCount")
                val total = cardCountObj?.get("total")?.takeIf { !it.isJsonNull }?.asInt
                    ?: cardCountObj?.get("official")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                if (total > 0) {
                    list.add(TcgSet(id, name, logoUrl, releaseDate, total))
                }
            }
            list.sortedByDescending { it.releaseDate ?: "" }
        }
    }

    suspend fun fetchSetSlots(set: TcgSet): List<CardSlot> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.tcgdex.net/v2/en/sets/${set.id}").build()
        NetClient.okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: return@withContext emptyList()
            val root = JsonParser.parseString(body).asJsonObject
            val setName = root.get("name")?.takeIf { !it.isJsonNull }?.asString ?: set.name
            val cardCountObj = root.getAsJsonObject("cardCount")
            val officialTotal = cardCountObj?.get("official")?.takeIf { !it.isJsonNull }?.asInt ?: set.totalCards
            val cardsArr = root.getAsJsonArray("cards") ?: return@withContext emptyList()

            val slots = mutableListOf<CardSlot>()
            for (el in cardsArr) {
                val obj = el.asJsonObject
                val cid = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val cname = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val localId = obj.get("localId")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val imgBase = obj.get("image")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val imageUrl = "$imgBase/low.webp"
                val rarity = obj.get("rarity")?.takeIf { !it.isJsonNull }?.asString ?: "Common"

                val variantsObj = obj.getAsJsonObject("variants")
                val finishes = mutableListOf<String>()
                if (variantsObj != null) {
                    if (variantsObj.get("normal")?.takeIf { !it.isJsonNull }?.asBoolean == true) finishes.add("Normal")
                    if (variantsObj.get("reverse")?.takeIf { !it.isJsonNull }?.asBoolean == true) finishes.add("Reverse Holofoil")
                    if (variantsObj.get("holo")?.takeIf { !it.isJsonNull }?.asBoolean == true) finishes.add("Holofoil")
                }
                if (finishes.isEmpty()) {
                    val rLow = rarity.lowercase()
                    if (rLow.contains("rare") && rLow != "rare") finishes.add("Holofoil")
                    else finishes.add("Normal")
                }

                for (fin in finishes) {
                    slots.add(
                        CardSlot(
                            slotId = "$cid::$fin",
                            cardId = cid,
                            setId = set.id,
                            setName = setName,
                            name = cname,
                            localId = localId,
                            setTotal = officialTotal,
                            rarity = rarity,
                            finish = fin,
                            imageUrl = imageUrl
                        )
                    )
                }
            }
            slots
        }
    }
}

// --- Local Storage ---
class LocalStore(ctx: Context) {
    private val gson = Gson()
    private val qtyFile = File(ctx.filesDir, "tcg_quantities_v1.json")
    private val setsFile = File(ctx.filesDir, "tcg_sets_v1.json")
    private val slotsFile = File(ctx.filesDir, "tcg_slots_v1.json")
    private val hashFile = File(ctx.filesDir, "tcg_hashes_v1.json")

    fun loadQuantities(): Map<String, Int> = try {
        if (!qtyFile.exists()) emptyMap()
        else gson.fromJson(qtyFile.readText(), object : TypeToken>() {}.type) ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    fun saveQuantities(map: Map<String, Int>) = qtyFile.writeText(gson.toJson(map))

    fun loadSets(): List<TcgSet> = try {
        if (!setsFile.exists()) emptyList()
        else gson.fromJson(setsFile.readText(), object : TypeToken<List<TcgSet>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun saveSets(sets: List<TcgSet>) = setsFile.writeText(gson.toJson(sets))

    fun loadAllSlots(): Map<String, List<CardSlot>> = try {
        if (!slotsFile.exists()) emptyMap()
        else gson.fromJson(slotsFile.readText(), object : TypeToken>>() {}.type) ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    fun saveAllSlots(map: Map<String, List<CardSlot>>) = slotsFile.writeText(gson.toJson(map))

    fun loadHashes(): Map<String, CardHashEntry> = try {
        if (!hashFile.exists()) emptyMap()
        else gson.fromJson(hashFile.readText(), object : TypeToken>() {}.type) ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    fun saveHashes(map: Map<String, CardHashEntry>) = hashFile.writeText(gson.toJson(map))
}

// --- Visual Matcher + OCR Assist ---
object CardRecognizer {
    fun computeSignature(bmp: Bitmap): Pair<Long, IntArray> {
        val gray = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val p1 = gray.getPixel(x, y)
                val p2 = gray.getPixel(x + 1, y)
                val l1 = (AndroidColor.red(p1) * 77 + AndroidColor.green(p1) * 150 + AndroidColor.blue(p1) * 29) shr 8
                val l2 = (AndroidColor.red(p2) * 77 + AndroidColor.green(p2) * 150 + AndroidColor.blue(p2) * 29) shr 8
                if (l1 > l2) hash = hash or (1L shl bit)
                bit++
            }
        }
        gray.recycle()

        val small = Bitmap.createScaledBitmap(bmp, 4, 4, true)
        val sig = IntArray(48)
        var idx = 0
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val px = small.getPixel(x, y)
                sig[idx++] = AndroidColor.red(px)
                sig[idx++] = AndroidColor.green(px)
                sig[idx++] = AndroidColor.blue(px)
            }
        }
        small.recycle()
        return hash to sig
    }

    fun matchCard(
        frameHash: Long,
        frameColor: IntArray,
        ocrText: String,
        candidateSlots: List<CardSlot>,
        hashes: Map<String, CardHashEntry>
    ): CardSlot? {
        if (candidateSlots.isEmpty()) return null
        val uniqueCards = candidateSlots.distinctBy { it.cardId }

        // 1. Проверяем, прочитался ли номер карты (например, 001/084 или 17/86) или точное имя в рамках этого пула
        val ocrClean = ocrText.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
        val slashMatch = Regex("""\b(\d{1,3})\s*/\s*\d{2,3}\b""").find(ocrClean)
        val detectedNum = slashMatch?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
        val ocrLower = ocrText.lowercase()

        var bestCard: CardSlot? = null
        var bestScore = Int.MAX_VALUE

        for (card in uniqueCards) {
            val hEntry = hashes[card.cardId]
            val hamming = if (hEntry != null) java.lang.Long.bitCount(frameHash xor hEntry.dHash) else 16
            var colorDiff = 0
            if (hEntry != null && hEntry.colorSig.size == 48) {
                for (i in 0 until 48) colorDiff += abs(frameColor[i] - hEntry.colorSig[i])
            } else {
                colorDiff = 25 * 48
            }

            var score = hamming * 35 + (colorDiff / 48)

            // Бонус за совпадение номера в OCR
            val cardNumClean = card.localId.trimStart('0').ifEmpty { "0" }
            if (detectedNum != null && detectedNum == cardNumClean) {
                score -= 260
            }
            // Бонус за совпадение имени покемона в OCR
            if (card.name.length >= 4 && ocrLower.contains(card.name.lowercase())) {
                score -= 220
            }

            if (score < bestScore) {
                bestScore = score
                bestCard = card
            }
        }

        return if (bestCard != null && bestScore <= 580) bestCard else null
    }
}

fun numericCardOrder(localId: String): Int {
    val digits = localId.filter { it.isDigit() }
    val base = digits.toIntOrNull() ?: 9999
    val hasPrefix = localId.firstOrNull()?.isLetter() == true
    return if (hasPrefix) 10000 + base else base
}

fun formatCardNumber(localId: String, total: Int): String {
    val paddedLocal = if (localId.all { it.isDigit() } && localId.length < 3) localId.padStart(3, '0') else localId
    val paddedTotal = total.toString().padStart(3, '0')
    return "$paddedLocal/$paddedTotal"
}

// --- Theme Colors ---
val BgBlack = Color(0xFF0B0D0E)
val CardSurface = Color(0xFF141719)
val BorderGray = Color(0xFF262B2E)
val AccentTeal = Color(0xFF36B3A8)
val TextMuted = Color(0xFF8E969C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BgBlack,
                    surface = CardSurface,
                    primary = AccentTeal
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = BgBlack) {
                    PokemonCatalogApp()
                }
            }
        }
    }
}

@Composable
fun PokemonCatalogApp() {
    val ctx = LocalContext.current
    val store = remember { LocalStore(ctx) }
    val scope = rememberCoroutineScope()

    var sets by remember { mutableStateOf(store.loadSets()) }
    var slotsBySet by remember { mutableStateOf(store.loadAllSlots()) }
    var quantities by remember { mutableStateOf(store.loadQuantities()) }
    var hashes by remember { mutableStateOf(store.loadHashes()) }

    var activeSet by remember { mutableStateOf<TcgSet?>(null) }
    var scannerScopeSet by remember { mutableStateOf<TcgSet?>(null) }
    var isScannerOpen by remember { mutableStateOf(false) }
    var loadingSets by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (sets.isEmpty()) loadingSets = true
        try {
            val remote = TcgDexRepository.fetchAllSets()
            if (remote.isNotEmpty()) {
                sets = remote
                store.saveSets(remote)
            }
        } catch (_: Exception) {
        } finally {
            loadingSets = false
        }
    }

    fun changeQty(slotId: String, delta: Int) {
        val cur = quantities[slotId] ?: 0
        val next = (cur + delta).coerceAtLeast(0)
        val updated = if (next == 0) quantities - slotId else quantities + (slotId to next)
        quantities = updated
        store.saveQuantities(updated)
    }

    fun ensureHashesForSlots(slots: List<CardSlot>) {
        val missing = slots.distinctBy { it.cardId }.filter { !hashes.containsKey(it.cardId) }
        if (missing.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val sem = Semaphore(8)
            val newEntries = coroutineScope {
                missing.map { slot ->
                    async {
                        sem.withPermit {
                            try {
                                val req = Request.Builder().url(slot.imageUrl).build()
                                NetClient.okHttp.newCall(req).execute().use { r ->
                                    val bytes = r.body?.bytes()
                                    if (bytes != null) {
                                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (bmp != null) {
                                            val (dHash, colorSig) = CardRecognizer.computeSignature(bmp)
                                            bmp.recycle()
                                            return@withPermit CardHashEntry(slot.cardId, slot.setId, dHash, colorSig)
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (newEntries.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val merged = hashes + newEntries.associateBy { it.cardId }
                    hashes = merged
                    store.saveHashes(merged)
                }
            }
        }
    }

    if (isScannerOpen) {
        BackHandler { isScannerOpen = false }
        val scopedSlots = remember(scannerScopeSet, slotsBySet) {
            val target = scannerScopeSet
            if (target != null) {
                slotsBySet[target.id] ?: emptyList()
            } else {
                slotsBySet.values.flatten()
            }
        }
        ScannerOverlayScreen(
            scopeSet = scannerScopeSet,
            candidateSlots = scopedSlots,
            quantities = quantities,
            hashes = hashes,
            onIncrementSlot = { slotId -> changeQty(slotId, 1) },
            onClose = { isScannerOpen = false }
        )
        return
    }

    val currentSet = activeSet
    if (currentSet != null) {
        BackHandler { activeSet = null }
        SetDetailScreen(
            set = currentSet,
            slots = slotsBySet[currentSet.id] ?: emptyList(),
            quantities = quantities,
            onSlotsLoaded = { loaded ->
                val updated = slotsBySet + (currentSet.id to loaded)
                slotsBySet = updated
                store.saveAllSlots(updated)
                ensureHashesForSlots(loaded)
            },
            onIncrement = { slotId -> changeQty(slotId, 1) },
            onDecrement = { slotId -> changeQty(slotId, -1) },
            onOpenScanner = {
                scannerScopeSet = currentSet
                isScannerOpen = true
            },
            onBack = { activeSet = null }
        )
    } else {
        SetsCatalogScreen(
            sets = sets,
            slotsBySet = slotsBySet,
            quantities = quantities,
            isLoading = loadingSets,
            onSelectSet = { s -> activeSet = s },
            onIncrementSlot = { slotId -> changeQty(slotId, 1) },
            onDecrementSlot = { slotId -> changeQty(slotId, -1) },
            onOpenGlobalScanner = {
                scannerScopeSet = null
                isScannerOpen = true
            }
        )
    }
}

// --- Screen 1: Sets Catalog + Global Search ---
@Composable
fun SetsCatalogScreen(
    sets: List<TcgSet>,
    slotsBySet: Map<String, List<CardSlot>>,
    quantities: Map<String, Int>,
    isLoading: Boolean,
    onSelectSet: (TcgSet) -> Unit,
    onIncrementSlot: (String) -> Unit,
    onDecrementSlot: (String) -> Unit,
    onOpenGlobalScanner: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchAllCardsMode by remember { mutableStateOf(false) }

    val filteredSets = remember(sets, query) {
        if (query.isBlank()) sets
        else sets.filter {
            it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
        }
    }

    val globalCardResults = remember(slotsBySet, query, searchAllCardsMode) {
        if (!searchAllCardsMode || query.isBlank()) emptyList()
        else slotsBySet.values.flatten().filter {
            it.name.contains(query, ignoreCase = true) || it.localId.contains(query, ignoreCase = true)
        }.take(80)
    }

    Column(Modifier.fillMaxSize().background(BgBlack).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("Pokemon Sets", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        // Поле поиска + значок камеры рядом
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        if (searchAllCardsMode) "Поиск карты во всех сетах..." else "Search by sets",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, null, tint = Color.White)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = BorderGray,
                    focusedContainerColor = CardSurface,
                    unfocusedContainerColor = CardSurface
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CardSurface)
                    .border(1.dp, BorderGray, CircleShape)
                    .clickable { onOpenGlobalScanner() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Сканер по всем сетам", tint = Color.White)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Переключатель: искать сеты или карты во всех сетах
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !searchAllCardsMode,
                onClick = { searchAllCardsMode = false },
                label = { Text("Сеты (${sets.size})", fontSize = 12.sp) }
            )
            FilterChip(
                selected = searchAllCardsMode,
                onClick = { searchAllCardsMode = true },
                label = { Text("Поиск карт по всем сетам", fontSize = 12.sp) }
            )
        }

        Spacer(Modifier.height(8.dp))

        if (isLoading && sets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else if (searchAllCardsMode) {
            if (query.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Введите имя покемона или номер карты", color = TextMuted, fontSize = 14.sp)
                }
            } else if (globalCardResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Ничего не найдено (откройте нужные сеты хотя бы раз)", color = TextMuted, fontSize = 13.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(globalCardResults, key = { it.slotId }) { slot ->
                        val qty = quantities[slot.slotId] ?: 0
                        CardGridItem(
                            slot = slot,
                            qty = qty,
                            onIncrement = { onIncrementSlot(slot.slotId) },
                            onDecrement = { onDecrementSlot(slot.slotId) }
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(filteredSets, key = { it.id }) { set ->
                    val setSlots = slotsBySet[set.id]
                    val totalSlots = setSlots?.size ?: set.totalCards
                    val collectedCount = setSlots?.count { (quantities[it.slotId] ?: 0) > 0 } ?: 0
                    val progress = if (totalSlots > 0) (collectedCount.toFloat() / totalSlots).coerceIn(0f, 1f) else 0f

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSet(set) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(104.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1B1E20))
                                .border(1.dp, BorderGray, RoundedCornerShape(12.dp))
                        ) {
                            if (set.logoUrl != null) {
                                AsyncImage(
                                    model = set.logoUrl,
                                    contentDescription = set.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp, vertical = 18.dp)
                                )
                            } else {
                                Text(
                                    text = set.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.align(Alignment.Center).padding(8.dp)
                                )
                            }

                            if (!set.releaseDate.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(set.releaseDate, color = Color(0xFFB0B8BE), fontSize = 10.sp)
                                }
                            }

                            if (collectedCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(16.dp)
                                        .background(Color(0xFF2A3033))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(progress)
                                            .background(AccentTeal)
                                    )
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = set.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Progress: $collectedCount/$totalSlots",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// --- Screen 2: Inside Selected Set ---
@Composable
fun SetDetailScreen(
    set: TcgSet,
    slots: List<CardSlot>,
    quantities: Map<String, Int>,
    onSlotsLoaded: (List<CardSlot>) -> Unit,
    onIncrement: (String) -> Unit,
    onDecrement: (String) -> Unit,
    onOpenScanner: () -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortAscending by remember { mutableStateOf(true) }
    var filterMode by remember { mutableStateOf("ALL") } // ALL, OWNED, MISSING
    var showSortMenu by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(set.id) {
        if (slots.isEmpty()) {
            isLoading = true
            errorText = null
            try {
                val loaded = TcgDexRepository.fetchSetSlots(set)
                onSlotsLoaded(loaded)
            } catch (e: Exception) {
                errorText = "Ошибка загрузки: ${e.message ?: "проверьте интернет"}"
            } finally {
                isLoading = false
            }
        }
    }

    val displayedSlots = remember(slots, searchQuery, sortAscending, filterMode, quantities) {
        val filtered = slots.filter { slot ->
            val qOk = searchQuery.isBlank() ||
                slot.name.contains(searchQuery, ignoreCase = true) ||
                slot.localId.contains(searchQuery, ignoreCase = true)
            val qty = quantities[slot.slotId] ?: 0
            val fOk = when (filterMode) {
                "OWNED" -> qty > 0
                "MISSING" -> qty == 0
                else -> true
            }
            qOk && fOk
        }
        if (sortAscending) {
            filtered.sortedWith(compareBy({ numericCardOrder(it.localId) }, { it.finish }))
        } else {
            filtered.sortedWith(compareByDescending<CardSlot> { numericCardOrder(it.localId) }.thenBy { it.finish })
        }
    }

    val totalSlots = if (slots.isNotEmpty()) slots.size else set.totalCards
    val collectedCount = slots.count { (quantities[it.slotId] ?: 0) > 0 }

    Column(Modifier.fillMaxSize().background(BgBlack).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Верхняя панель как на скриншоте 2 и 3: Назад + Камера + Поиск + Сортировка/Фильтр
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(CardSurface)
                    .border(1.dp, BorderGray, CircleShape)
                    .clickable { onOpenScanner() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Сканировать сет", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search for products", color = TextMuted, fontSize = 13.sp) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentTeal,
                    unfocusedBorderColor = BorderGray,
                    focusedContainerColor = CardSurface,
                    unfocusedContainerColor = CardSurface
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Box {
                IconButton(
                    onClick = { showSortMenu = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CardSurface)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Sort & Filter", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    containerColor = Color(0xFF1D2124)
                ) {
                    DropdownMenuItem(
                        text = { Text("123  Card Number: Low to High", color = if (sortAscending) AccentTeal else Color.White) },
                        onClick = { sortAscending = true; showSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("321  Card Number: High to Low", color = if (!sortAscending) AccentTeal else Color.White) },
                        onClick = { sortAscending = false; showSortMenu = false }
                    )
                    HorizontalDivider(color = BorderGray)
                    DropdownMenuItem(
                        text = { Text("Показать: Все карты", color = if (filterMode == "ALL") AccentTeal else Color.White) },
                        onClick = { filterMode = "ALL"; showSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Показать: Собранные (Qty > 0)", color = if (filterMode == "OWNED") AccentTeal else Color.White) },
                        onClick = { filterMode = "OWNED"; showSortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Показать: Недостающие (Qty: 0)", color = if (filterMode == "MISSING") AccentTeal else Color.White) },
                        onClick = { filterMode = "MISSING"; showSortMenu = false }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Шапка сета с логотипом и прогрессом
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp, 46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (set.logoUrl != null) {
                    AsyncImage(
                        model = set.logoUrl,
                        contentDescription = set.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                } else {
                    Text(set.id.uppercase(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(set.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Progress: $collectedCount/$totalSlots", color = TextMuted, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else if (errorText != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(errorText ?: "", color = Color(0xFFEF5350))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayedSlots, key = { it.slotId }) { slot ->
                    val qty = quantities[slot.slotId] ?: 0
                    CardGridItem(
                        slot = slot,
                        qty = qty,
                        onIncrement = { onIncrement(slot.slotId) },
                        onDecrement = { onDecrement(slot.slotId) }
                    )
                }
            }
        }
    }
}

@Composable
fun CardGridItem(
    slot: CardSlot,
    qty: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    val isOwned = qty > 0
    val grayscaleFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, if (isOwned) AccentTeal.copy(alpha = 0.45f) else BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = slot.imageUrl,
                    contentDescription = slot.name,
                    contentScale = ContentScale.FillWidth,
                    colorFilter = if (isOwned) null else grayscaleFilter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .alpha(if (isOwned) 1f else 0.55f)
                )
                if (isOwned) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(AccentTeal),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = slot.name,
                color = if (isOwned) Color.White else Color(0xFFB0B8BE),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = slot.setName,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${slot.rarity} • ${formatCardNumber(slot.localId, slot.setTotal)}",
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = slot.finish,
                color = if (slot.finish == "Normal") TextMuted else AccentTeal,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Qty: $qty",
                    color = if (isOwned) Color.White else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = qty > 0) { onDecrement() }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White.copy(alpha = 0.75f), CircleShape)
                        .clickable { onIncrement() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// --- Screen 3: Scoped Scanner ---
fun extractCardCropBitmap(image: Image, rotationDegrees: Int): Bitmap? {
    return try {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val rawStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 85, rawStream)
        val rawBytes = rawStream.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null

        val rotated = if (rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also {
                if (it != bmp) bmp.recycle()
            }
        } else bmp

        val w = rotated.width
        val h = rotated.height
        val targetRatio = 2.5f / 3.5f
        var cropW = (w * 0.80f).toInt()
        var cropH = (cropW / targetRatio).toInt()
        if (cropH > h * 0.68f) {
            cropH = (h * 0.68f).toInt()
            cropW = (cropH * targetRatio).toInt()
        }
        val left = ((w - cropW) / 2).coerceAtLeast(0)
        val top = ((h - cropH) / 2 + (h * 0.03f).toInt()).coerceIn(0, (h - cropH).coerceAtLeast(0))

        val cropped = Bitmap.createBitmap(rotated, left, top, cropW.coerceAtMost(w - left), cropH.coerceAtMost(h - top))
        if (cropped != rotated) rotated.recycle()
        cropped
    } catch (_: Exception) {
        null
    }
}

@Composable
fun ScannerOverlayScreen(
    scopeSet: TcgSet?,
    candidateSlots: List<CardSlot>,
    quantities: Map<String, Int>,
    hashes: Map<String, CardHashEntry>,
    onIncrementSlot: (String) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var perm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    LaunchedEffect(Unit) { if (!perm) launch.launch(Manifest.permission.CAMERA) }

    var statusText by remember {
        mutableStateOf(
            if (scopeSet != null) "Сканирую сет: ${scopeSet.name}"
            else "Сканирую все открытые сеты (${candidateSlots.distinctBy { it.cardId }.size} карт)"
        )
    }
    var detectedCard by remember { mutableStateOf<CardSlot?>(null) }
    var lastAddedCardId by remember { mutableStateOf("") }
    var lastMatchedHash by remember { mutableLongStateOf(0L) }
    var streakId by remember { mutableStateOf("") }
    var streakCount by remember { mutableIntStateOf(0) }
    var bannerText by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var extraRot by remember { mutableIntStateOf(0) }
    var camCtrl by remember { mutableStateOf<Camera?>(null) }

    val curSlots by rememberUpdatedState(candidateSlots)
    val curHashes by rememberUpdatedState(hashes)
    val curExtraRot by rememberUpdatedState(extraRot)

    val tone = remember { try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95) } catch (_: Exception) { null } }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    fun beepAndVib() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 130) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(VibrationEffect.createOneShot(60, -1))
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(60)
        } catch (_: Exception) {}
    }

    LaunchedEffect(torch, camCtrl) { try { camCtrl?.cameraControl?.enableTorch(torch) } catch (_: Exception) {} }
    LaunchedEffect(bannerText) { if (bannerText != null) { delay(1300); bannerText = null } }

    if (!perm) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = { launch.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c ->
            PreviewView(c).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }.also { pv ->
                ProcessCameraProvider.getInstance(c).addListener({
                    val prov = ProcessCameraProvider.getInstance(c).get()
                    val prev = Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }
                    val an = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val textRec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    var lastT = 0L

                    an.setAnalyzer(Executors.newSingleThreadExecutor()) { px ->
                        val now = System.currentTimeMillis()
                        if (now - lastT < 220) { px.close(); return@setAnalyzer }
                        @androidx.camera.core.ExperimentalGetImage val img = px.image
                        if (img != null) {
                            lastT = now
                            val rot = (px.imageInfo.rotationDegrees + curExtraRot) % 360
                            val crop = extractCardCropBitmap(img, rot)
                            if (crop != null) {
                                val (frameHash, frameColor) = CardRecognizer.computeSignature(crop)
                                val inputImg = InputImage.fromBitmap(crop, 0)
                                textRec.process(inputImg)
                                    .addOnSuccessListener { visionText ->
                                        val match = CardRecognizer.matchCard(
                                            frameHash = frameHash,
                                            frameColor = frameColor,
                                            ocrText = visionText.text,
                                            candidateSlots = curSlots,
                                            hashes = curHashes
                                        )
                                        if (match == null) {
                                            streakId = ""
                                            streakCount = 0
                                            if (lastAddedCardId.isNotEmpty() && java.lang.Long.bitCount(frameHash xor lastMatchedHash) > 18) {
                                                lastAddedCardId = ""
                                                statusText = "Жду следующую карту..."
                                            }
                                        } else {
                                            if (match.cardId == streakId) streakCount++ else { streakId = match.cardId; streakCount = 1 }
                                            if (streakCount >= 2 && match.cardId != lastAddedCardId) {
                                                lastAddedCardId = match.cardId
                                                lastMatchedHash = frameHash
                                                val defaultSlot = curSlots.firstOrNull { it.cardId == match.cardId && it.finish == "Normal" } ?: match
                                                scope.launch(Dispatchers.Main) {
                                                    detectedCard = defaultSlot
                                                    onIncrementSlot(defaultSlot.slotId)
                                                    beepAndVib()
                                                    statusText = "Найдено: ${defaultSlot.name} (${formatCardNumber(defaultSlot.localId, defaultSlot.setTotal)})"
                                                    bannerText = "+1 ${defaultSlot.name} [${defaultSlot.finish}]"
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener {
                                        crop.recycle()
                                        px.close()
                                    }
                            } else {
                                px.close()
                            }
                        } else {
                            px.close()
                        }
                    }

                    try {
                        prov.unbindAll()
                        camCtrl = prov.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(c))
            }
        }, modifier = Modifier.fillMaxSize())

        // Большая рамка под пропорции карты 2.5 x 3.5
        Canvas(modifier = Modifier.fillMaxSize()) {
            val targetRatio = 2.5f / 3.5f
            var boxW = size.width * 0.82f
            var boxH = boxW / targetRatio
            if (boxH > size.height * 0.66f) {
                boxH = size.height * 0.66f
                boxW = boxH * targetRatio
            }
            val left = (size.width - boxW) / 2f
            val top = (size.height - boxH) / 2f + size.height * 0.03f

            drawRoundRect(
                color = if (lastAddedCardId.isNotEmpty()) AccentTeal else Color.White.copy(alpha = 0.88f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(28f, 28f),
                style = Stroke(width = 7f)
            )
        }

        // Верхняя плашка сканера
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color.Black.copy(0.84f), shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть", tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(
                            text = scopeSet?.name ?: "Все сеты",
                            color = AccentTeal,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = statusText,
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { torch = !torch }, modifier = Modifier.size(34.dp)) {
                        Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, null, tint = if (torch) Color(0xFFFFD54F) else Color.White)
                    }
                    IconButton(onClick = { extraRot = (extraRot + 90) % 360; lastAddedCardId = "" }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.RotateRight, null, tint = Color.White)
                    }
                }
            }
            bannerText?.let {
                Spacer(Modifier.height(6.dp))
                Surface(color = AccentTeal, shape = RoundedCornerShape(20.dp)) {
                    Text(it, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 6.dp))
                }
            }
        }

        // Нижняя карточка последней распознанной карты с выбором слота вариации (Normal / Reverse Holofoil)
        detectedCard?.let { card ->
            val siblingSlots = remember(card.cardId, candidateSlots) {
                candidateSlots.filter { it.cardId == card.cardId }
            }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = BorderStroke(1.dp, AccentTeal),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = card.name,
                        modifier = Modifier.size(56.dp, 78.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${card.setName} • ${formatCardNumber(card.localId, card.setTotal)}", color = TextMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            siblingSlots.forEach { s ->
                                val q = quantities[s.slotId] ?: 0
                                FilterChip(
                                    selected = q > 0,
                                    onClick = {
                                        onIncrementSlot(s.slotId)
                                        beepAndVib()
                                        bannerText = "+1 ${s.name} [${s.finish}]"
                                    },
                                    label = {
                                        Text(
                                            text = "${if (s.finish == "Reverse Holofoil") "Reverse" else s.finish} ($q)",
                                            fontSize = 11.sp
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
