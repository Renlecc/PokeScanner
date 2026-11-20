package com.ncorti.kotlin.template.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.media.AudioManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.io.File
import java.net.URLEncoder
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
    val slotId: String,
    val cardId: String,
    val setId: String,
    val setName: String,
    val name: String,
    val localId: String,
    val setTotal: Int,
    val rarity: String,
    val finish: String,
    val imageUrl: String
)

data class CardHashEntry(
    val cardId: String,
    val setId: String,
    val dHash: Long,
    val colorSig: IntArray
)

data class PendingScanItem(
    val uid: Long,
    val slot: CardSlot,
    val needsCheck: Boolean
)

data class CardMatchResult(
    val card: CardSlot,
    val needsCheck: Boolean
)

object NetClient {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
}

// --- TCGdex API Service ---
object TcgDexRepository {
    private fun generateSlotsForCard(
        cid: String,
        setId: String,
        setName: String,
        cname: String,
        localId: String,
        officialTotal: Int,
        rarity: String,
        imageUrl: String
    ): List<CardSlot> {
        val numVal = localId.filter { it.isDigit() }.toIntOrNull() ?: 0
        val rLow = rarity.lowercase()
        val nameLow = cname.lowercase()

        val isUltraOrSecret = (officialTotal > 0 && numVal > officialTotal) ||
            rLow.contains("ultra") ||
            rLow.contains("illustration") ||
            rLow.contains("hyper") ||
            rLow.contains("secret") ||
            rLow.contains("double rare") ||
            rLow.contains("shiny") ||
            rLow.contains("ace spec") ||
            nameLow.endsWith(" ex") ||
            nameLow.endsWith(" vmax") ||
            nameLow.endsWith(" vstar") ||
            nameLow.endsWith(" gx") ||
            nameLow.endsWith(" v")

        val finishes = when {
            isUltraOrSecret -> listOf("Holofoil")
            rLow == "rare holo" || rLow == "holo rare" -> listOf("Holofoil", "Reverse Holofoil")
            else -> listOf("Normal", "Reverse Holofoil")
        }

        return finishes.map { fin ->
            CardSlot(
                slotId = "$cid::$fin",
                cardId = cid,
                setId = setId,
                setName = setName,
                name = cname,
                localId = localId,
                setTotal = officialTotal,
                rarity = rarity,
                finish = fin,
                imageUrl = imageUrl
            )
        }
    }

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
            list.sortedWith(
                compareBy<TcgSet> { it.releaseDate.isNullOrBlank() }
                    .thenByDescending { it.releaseDate ?: "" }
            )
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

                slots.addAll(
                    generateSlotsForCard(cid, set.id, setName, cname, localId, officialTotal, rarity, imageUrl)
                )
            }
            slots
        }
    }

    suspend fun searchGlobalCards(query: String, setsMap: Map<String, TcgSet>): List<CardSlot> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val req = Request.Builder().url("https://api.tcgdex.net/v2/en/cards?name=$encoded").build()
        NetClient.okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val arr = JsonParser.parseString(body).asJsonArray
            val slots = mutableListOf<CardSlot>()

            for (el in arr) {
                if (slots.size >= 120) break
                val obj = el.asJsonObject
                val cid = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val cname = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val localId = obj.get("localId")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val imgBase = obj.get("image")?.takeIf { !it.isJsonNull }?.asString ?: continue
                val imageUrl = "$imgBase/low.webp"

                val setId = cid.substringBeforeLast("-", "")
                val knownSet = setsMap[setId]
                val setName = knownSet?.name ?: setId.uppercase()
                val officialTotal = knownSet?.totalCards ?: 0

                slots.addAll(
                    generateSlotsForCard(cid, setId, setName, cname, localId, officialTotal, "Card", imageUrl)
                )
            }
            slots
        }
    }
}

// --- Local Storage ---
class LocalStore(ctx: Context) {
    private val gson = Gson()
    private val qtyFile = File(ctx.filesDir, "tcg_quantities_v2.json")
    private val setsFile = File(ctx.filesDir, "tcg_sets_v2.json")
    private val slotsFile = File(ctx.filesDir, "tcg_slots_v2.json")
    private val hashFile = File(ctx.filesDir, "tcg_hashes_v2.json")

    private inline fun <reified T> readJson(file: File, fallback: T): T {
        return try {
            if (!file.exists()) fallback
            else gson.fromJson<T>(file.readText(), object : TypeToken<T>() {}.type) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun loadQuantities(): Map<String, Int> = readJson(qtyFile, emptyMap())
    fun saveQuantities(map: Map<String, Int>) = qtyFile.writeText(gson.toJson(map))

    fun loadSets(): List<TcgSet> = readJson(setsFile, emptyList())
    fun saveSets(sets: List<TcgSet>) = setsFile.writeText(gson.toJson(sets))

    fun loadAllSlots(): Map<String, List<CardSlot>> = readJson(slotsFile, emptyMap())
    fun saveAllSlots(map: Map<String, List<CardSlot>>) = slotsFile.writeText(gson.toJson(map))

    fun loadHashes(): Map<String, CardHashEntry> = readJson(hashFile, emptyMap())
    fun saveHashes(map: Map<String, CardHashEntry>) = hashFile.writeText(gson.toJson(map))
}

// --- Visual Matcher + OCR Primary ---
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

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = i - 1
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }

    private fun nameMatches(cardName: String, nameZoneText: String): Boolean {
        val target = cardName.lowercase().trim()
        if (target.length < 2) return false
        val cleaned = nameZoneText.lowercase().replace(Regex("[^a-z0-9\\s'-]"), " ")
        if (cleaned.contains(target)) return true

        val words = cleaned.split(Regex("\\s+")).filter { it.length >= 3 }
        for (w in words) {
            if (abs(w.length - target.length) <= 1 && levenshtein(w, target) <= 1) {
                return true
            }
        }
        for (i in 0 until words.size - 1) {
            val pair = "${words[i]} ${words[i + 1]}"
            if (abs(pair.length - target.length) <= 1 && levenshtein(pair, target) <= 1) {
                return true
            }
        }
        return false
    }

    private fun visualDistanceScore(
        card: CardSlot,
        frameHash: Long,
        frameColor: IntArray,
        hashes: Map<String, CardHashEntry>
    ): Int {
        val hEntry = hashes[card.cardId] ?: return Int.MAX_VALUE
        val hamming = java.lang.Long.bitCount(frameHash xor hEntry.dHash)
        var colorDiff = 0
        if (hEntry.colorSig.size == 48) {
            for (i in 0 until 48) colorDiff += abs(frameColor[i] - hEntry.colorSig[i])
        } else {
            colorDiff = 25 * 48
        }
        return hamming * 35 + (colorDiff / 48)
    }

    fun matchCard(
        frameHash: Long,
        frameColor: IntArray,
        bottomLeftText: String,
        topNameText: String,
        isScopedSet: Boolean,
        candidateSlots: List<CardSlot>,
        hashes: Map<String, CardHashEntry>
    ): CardMatchResult? {
        if (candidateSlots.isEmpty()) return null

        val numRaw = bottomLeftText
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')

        val slashMatch = Regex("""\b(\d{1,3})\s*/\s*(\d{2,3})\b""").find(numRaw)
        val parsedLocalNum = slashMatch?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
            ?: if (isScopedSet) {
                Regex("""\b(\d{1,3})\b""").find(numRaw)?.groupValues?.get(1)?.trimStart('0')?.ifEmpty { "0" }
            } else {
                null
            }
        val parsedSetTotal = slashMatch?.groupValues?.get(2)?.toIntOrNull()

        val hasNameText = topNameText.trim().length >= 3
        if (parsedLocalNum == null && !hasNameText) return null

        val uniqueCards = candidateSlots.distinctBy { it.cardId }

        // Глобальный режим: строго требуется "/TTT" и совпадение setTotal
        if (!isScopedSet) {
            if (parsedLocalNum == null || parsedSetTotal == null) return null
            val matching = uniqueCards.filter { card ->
                val cNum = card.localId.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
                cNum == parsedLocalNum && card.setTotal == parsedSetTotal
            }
            if (matching.isEmpty()) return null
            val sorted = matching.sortedBy { visualDistanceScore(it, frameHash, frameColor, hashes) }
            if (sorted.size == 1) {
                return CardMatchResult(sorted.first(), needsCheck = false)
            }
            val byName = sorted.firstOrNull { nameMatches(it.name, topNameText) }
            return if (byName != null) {
                CardMatchResult(byName, needsCheck = false)
            } else {
                CardMatchResult(sorted.first(), needsCheck = true)
            }
        }

        // Режим внутри сета
        if (parsedLocalNum != null) {
            val numCandidates = uniqueCards.filter { card ->
                val cNum = card.localId.filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
                cNum == parsedLocalNum
            }
            if (numCandidates.isEmpty()) return null

            val sorted = numCandidates.sortedBy { visualDistanceScore(it, frameHash, frameColor, hashes) }
            if (sorted.size == 1) {
                return CardMatchResult(sorted.first(), needsCheck = false)
            }

            val nameMatched = sorted.filter { nameMatches(it.name, topNameText) }
            if (nameMatched.size == 1) {
                return CardMatchResult(nameMatched.first(), needsCheck = false)
            }
            if (nameMatched.size > 1) {
                return CardMatchResult(nameMatched.first(), needsCheck = true)
            }
            return CardMatchResult(sorted.first(), needsCheck = true)
        }

        // Номер не прочитан, пробуем только по имени внутри сета
        if (hasNameText) {
            val nameCandidates = uniqueCards
                .filter { nameMatches(it.name, topNameText) }
                .sortedBy { visualDistanceScore(it, frameHash, frameColor, hashes) }
            if (nameCandidates.size == 1) {
                return CardMatchResult(nameCandidates.first(), needsCheck = false)
            }
            if (nameCandidates.size > 1) {
                return CardMatchResult(nameCandidates.first(), needsCheck = true)
            }
        }

        return null
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
    return if (total > 0) {
        val paddedTotal = total.toString().padStart(3, '0')
        "$paddedLocal/$paddedTotal"
    } else {
        "#$paddedLocal"
    }
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

        if (next > 0) {
            val setId = slotId.substringBefore("-", "")
            if (setId.isNotEmpty() && !slotsBySet.containsKey(setId)) {
                val targetSet = sets.find { it.id == setId }
                if (targetSet != null) {
                    scope.launch {
                        try {
                            val loaded = TcgDexRepository.fetchSetSlots(targetSet)
                            val updatedSlots = slotsBySet + (targetSet.id to loaded)
                            slotsBySet = updatedSlots
                            store.saveAllSlots(updatedSlots)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
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
    var remoteCardResults by remember { mutableStateOf<List<CardSlot>>(emptyList()) }
    var isSearchingCards by remember { mutableStateOf(false) }

    val setsMap = remember(sets) { sets.associateBy { it.id } }

    LaunchedEffect(query, searchAllCardsMode) {
        if (!searchAllCardsMode) {
            remoteCardResults = emptyList()
            isSearchingCards = false
            return@LaunchedEffect
        }
        val q = query.trim()
        if (q.length < 2) {
            remoteCardResults = emptyList()
            isSearchingCards = false
            return@LaunchedEffect
        }
        delay(400)
        isSearchingCards = true
        try {
            val localMatches = slotsBySet.values.flatten().filter {
                it.name.contains(q, ignoreCase = true) || it.localId.contains(q, ignoreCase = true)
            }
            val apiMatches = TcgDexRepository.searchGlobalCards(q, setsMap)
            val merged = (localMatches + apiMatches).distinctBy { it.slotId }
            remoteCardResults = merged
        } catch (_: Exception) {
            remoteCardResults = slotsBySet.values.flatten().filter {
                it.name.contains(q, ignoreCase = true) || it.localId.contains(q, ignoreCase = true)
            }
        } finally {
            isSearchingCards = false
        }
    }

    val displayedSets = remember(sets, slotsBySet, quantities, query) {
        if (query.isNotBlank()) {
            sets.filter {
                it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
            }
        } else {
            sets.filter { set ->
                val setSlots = slotsBySet[set.id]
                val hasInSlots = setSlots?.any { (quantities[it.slotId] ?: 0) > 0 } == true
                val hasInQuantities = quantities.keys.any { it.startsWith("${set.id}-") }
                hasInSlots || hasInQuantities
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        if (searchAllCardsMode) "Имя карты (от 2 букв)..." else "Search by sets (${sets.size})",
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !searchAllCardsMode,
                onClick = { searchAllCardsMode = false },
                label = { Text("Мои сеты (${displayedSets.size})", fontSize = 12.sp) }
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
            if (query.trim().length < 2) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Введите минимум 2 буквы имени покемона", color = TextMuted, fontSize = 14.sp)
                }
            } else if (isSearchingCards) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentTeal)
                }
            } else if (remoteCardResults.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Карты не найдены", color = TextMuted, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(remoteCardResults, key = { it.slotId }) { slot ->
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
        } else if (displayedSets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isBlank()) "Начните вводить название сета в поиске сверху,\nчтобы открыть его и добавить карты"
                    else "Сет не найден",
                    color = TextMuted,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(displayedSets, key = { it.id }) { set ->
                    val setSlots = slotsBySet[set.id]
                    val totalSlots = setSlots?.size ?: set.totalCards
                    val collectedCount = setSlots?.count { (quantities[it.slotId] ?: 0) > 0 }
                        ?: quantities.keys.count { it.startsWith("${set.id}-") }

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
                                        .padding(horizontal = 14.dp, vertical = 14.dp)
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

    val gridState = rememberLazyGridState()

    LaunchedEffect(sortAscending, filterMode) {
        gridState.scrollToItem(0)
    }

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
            filtered.sortedWith(
                compareBy({ numericCardOrder(it.localId) }, { if (it.finish == "Reverse Holofoil") 1 else 0 })
            )
        } else {
            filtered.sortedWith(
                compareByDescending<CardSlot> { numericCardOrder(it.localId) }
                    .thenBy { if (it.finish == "Reverse Holofoil") 1 else 0 }
            )
        }
    }

    val totalSlots = if (slots.isNotEmpty()) slots.size else set.totalCards
    val collectedCount = slots.count { (quantities[it.slotId] ?: 0) > 0 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
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
                        onClick = {
                            sortAscending = true
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("321  Card Number: High to Low", color = if (!sortAscending) AccentTeal else Color.White) },
                        onClick = {
                            sortAscending = false
                            showSortMenu = false
                        }
                    )
                    HorizontalDivider(color = BorderGray)
                    DropdownMenuItem(
                        text = { Text("Показать: Все карты", color = if (filterMode == "ALL") AccentTeal else Color.White) },
                        onClick = {
                            filterMode = "ALL"
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Показать: Есть (Qty > 0)", color = if (filterMode == "OWNED") AccentTeal else Color.White) },
                        onClick = {
                            filterMode = "OWNED"
                            showSortMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Показать: Нету (Qty: 0)", color = if (filterMode == "MISSING") AccentTeal else Color.White) },
                        onClick = {
                            filterMode = "MISSING"
                            showSortMenu = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

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
                state = gridState,
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
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .border(
                                1.5.dp,
                                if (qty > 0) Color.White.copy(alpha = 0.75f) else BorderGray,
                                CircleShape
                            )
                            .clickable(enabled = qty > 0) { onDecrement() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = "Убавить",
                            tint = if (qty > 0) Color.White else TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.75f), CircleShape)
                            .clickable { onIncrement() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Добавить",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// --- Screen 3: Scoped & Batch Scanner ---
fun extractCardCropBitmap(imageProxy: ImageProxy, extraRotationDegrees: Int): Bitmap? {
    return try {
        val rawBmp = imageProxy.toBitmap()
        val totalRotation = (imageProxy.imageInfo.rotationDegrees + extraRotationDegrees) % 360
        val rotated = if (totalRotation != 0) {
            val m = Matrix().apply { postRotate(totalRotation.toFloat()) }
            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true).also {
                if (it != rawBmp) rawBmp.recycle()
            }
        } else {
            rawBmp
        }

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

        val cropped = Bitmap.createBitmap(
            rotated,
            left,
            top,
            cropW.coerceAtMost(w - left),
            cropH.coerceAtMost(h - top)
        )
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

    var perm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    LaunchedEffect(Unit) { if (!perm) launch.launch(Manifest.permission.CAMERA) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camCtrl by remember { mutableStateOf<Camera?>(null) }

    val tone = remember { try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95) } catch (_: Exception) { null } }

    DisposableEffect(Unit) {
        onDispose {
            try { cameraProviderRef?.unbindAll() } catch (_: Exception) {}
            try { textRecognizer.close() } catch (_: Exception) {}
            try { analysisExecutor.shutdown() } catch (_: Exception) {}
            try { tone?.release() } catch (_: Exception) {}
        }
    }

    var selectedBatchFinish by remember { mutableStateOf("Normal") }
    var pendingBatch by remember { mutableStateOf<List<PendingScanItem>>(emptyList()) }
    var uidCounter by remember { mutableLongStateOf(1L) }

    var statusText by remember {
        mutableStateOf(
            if (scopeSet != null) "Сет: ${scopeSet.name}"
            else "Глобальный режим (${candidateSlots.distinctBy { it.cardId }.size} карт)"
        )
    }
    var bannerText by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var extraRot by remember { mutableIntStateOf(0) }

    val lockedCardIdRef = remember { mutableStateOf("") }
    val emptyFramesRef = remember { mutableIntStateOf(0) }
    val diffStreakIdRef = remember { mutableStateOf("") }
    val diffStreakCountRef = remember { mutableIntStateOf(0) }
    val streakCardIdRef = remember { mutableStateOf("") }
    val streakCountRef = remember { mutableIntStateOf(0) }

    val curSlots by rememberUpdatedState(candidateSlots)
    val curHashes by rememberUpdatedState(hashes)
    val curExtraRot by rememberUpdatedState(extraRot)
    val curBatchFinish by rememberUpdatedState(selectedBatchFinish)
    val curScopeSet by rememberUpdatedState(scopeSet)

    fun beepAndVib() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 130) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(60, -1))
            } else {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(60)
            }
        } catch (_: Exception) {}
    }

    fun resetDuplicateLock() {
        lockedCardIdRef.value = ""
        emptyFramesRef.intValue = 0
        diffStreakIdRef.value = ""
        diffStreakCountRef.intValue = 0
        streakCardIdRef.value = ""
        streakCountRef.intValue = 0
        statusText = "Готов к следующей карте"
    }

    LaunchedEffect(torch, camCtrl) {
        try { camCtrl?.cameraControl?.enableTorch(torch) } catch (_: Exception) {}
    }
    LaunchedEffect(bannerText) {
        if (bannerText != null) {
            delay(1300)
            bannerText = null
        }
    }

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
                    cameraProviderRef = prov
                    val prev = Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }
                    val an = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    var lastT = 0L

                    an.setAnalyzer(analysisExecutor) { px ->
                        val now = System.currentTimeMillis()
                        if (now - lastT < 200) {
                            px.close()
                            return@setAnalyzer
                        }
                        lastT = now

                        val crop = extractCardCropBitmap(px, curExtraRot)
                        px.close()

                        if (crop == null) return@setAnalyzer

                        val (frameHash, frameColor) = CardRecognizer.computeSignature(crop)
                        val cw = crop.width
                        val ch = crop.height

                        val topH = (ch * 0.22f).toInt().coerceIn(1, ch)
                        val botTop = (ch * 0.78f).toInt().coerceIn(0, ch - 1)
                        val botH = (ch - botTop).coerceAtLeast(1)
                        val botW = (cw * 0.58f).toInt().coerceIn(1, cw)

                        val topCrop = Bitmap.createBitmap(crop, 0, 0, cw, topH)
                        val botLeftCrop = Bitmap.createBitmap(crop, 0, botTop, botW, botH)
                        crop.recycle()

                        val topTask = textRecognizer.process(InputImage.fromBitmap(topCrop, 0))
                        val botTask = textRecognizer.process(InputImage.fromBitmap(botLeftCrop, 0))

                        com.google.android.gms.tasks.Tasks.whenAllComplete(topTask, botTask)
                            .addOnCompleteListener {
                                val topText = if (topTask.isSuccessful) topTask.result?.text ?: "" else ""
                                val botText = if (botTask.isSuccessful) botTask.result?.text ?: "" else ""
                                topCrop.recycle()
                                botLeftCrop.recycle()

                                val isFrameEmpty = topText.isBlank() && botText.isBlank()
                                if (isFrameEmpty) {
                                    streakCardIdRef.value = ""
                                    streakCountRef.intValue = 0
                                    diffStreakIdRef.value = ""
                                    diffStreakCountRef.intValue = 0
                                    if (lockedCardIdRef.value.isNotEmpty()) {
                                        emptyFramesRef.intValue += 1
                                        if (emptyFramesRef.intValue >= 5) {
                                            lockedCardIdRef.value = ""
                                            emptyFramesRef.intValue = 0
                                            scope.launch(Dispatchers.Main) {
                                                statusText = "Рамка пуста — готов к следующей"
                                            }
                                        }
                                    }
                                    return@addOnCompleteListener
                                } else {
                                    emptyFramesRef.intValue = 0
                                }

                                val matchRes = CardRecognizer.matchCard(
                                    frameHash = frameHash,
                                    frameColor = frameColor,
                                    bottomLeftText = botText,
                                    topNameText = topText,
                                    isScopedSet = curScopeSet != null,
                                    candidateSlots = curSlots,
                                    hashes = curHashes
                                )

                                if (matchRes == null) {
                                    streakCardIdRef.value = ""
                                    streakCountRef.intValue = 0
                                    return@addOnCompleteListener
                                }

                                val matchedId = matchRes.card.cardId

                                if (lockedCardIdRef.value.isNotEmpty()) {
                                    if (matchedId == lockedCardIdRef.value) {
                                        diffStreakIdRef.value = ""
                                        diffStreakCountRef.intValue = 0
                                        return@addOnCompleteListener
                                    } else {
                                        if (diffStreakIdRef.value == matchedId) {
                                            diffStreakCountRef.intValue += 1
                                        } else {
                                            diffStreakIdRef.value = matchedId
                                            diffStreakCountRef.intValue = 1
                                        }
                                        if (diffStreakCountRef.intValue >= 2) {
                                            lockedCardIdRef.value = ""
                                            diffStreakIdRef.value = ""
                                            diffStreakCountRef.intValue = 0
                                        } else {
                                            return@addOnCompleteListener
                                        }
                                    }
                                }

                                if (streakCardIdRef.value == matchedId) {
                                    streakCountRef.intValue += 1
                                } else {
                                    streakCardIdRef.value = matchedId
                                    streakCountRef.intValue = 1
                                }

                                if (streakCountRef.intValue >= 2 && lockedCardIdRef.value != matchedId) {
                                    lockedCardIdRef.value = matchedId
                                    streakCardIdRef.value = ""
                                    streakCountRef.intValue = 0

                                    val cardVariants = curSlots.filter { it.cardId == matchedId }
                                    val chosenFinish = curBatchFinish
                                    val resolvedSlot: CardSlot
                                    val needsCheckFlag: Boolean

                                    if (cardVariants.size == 1) {
                                        resolvedSlot = cardVariants.first()
                                        needsCheckFlag = matchRes.needsCheck
                                    } else {
                                        val exact = cardVariants.firstOrNull { it.finish == chosenFinish }
                                        if (exact != null) {
                                            resolvedSlot = exact
                                            needsCheckFlag = matchRes.needsCheck
                                        } else {
                                            resolvedSlot = cardVariants.firstOrNull() ?: matchRes.card
                                            needsCheckFlag = true
                                        }
                                    }

                                    scope.launch(Dispatchers.Main) {
                                        val nextUid = uidCounter++
                                        pendingBatch = pendingBatch + PendingScanItem(
                                            uid = nextUid,
                                            slot = resolvedSlot,
                                            needsCheck = needsCheckFlag
                                        )
                                        beepAndVib()
                                        val tag = if (needsCheckFlag) "проверить" else "ок"
                                        statusText = "В пачку: ${resolvedSlot.name} (${formatCardNumber(resolvedSlot.localId, resolvedSlot.setTotal)}) [$tag]"
                                        bannerText = "+ ${resolvedSlot.name} (${resolvedSlot.finish})"
                                    }
                                }
                            }
                    }

                    try {
                        prov.unbindAll()
                        camCtrl = prov.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(c))
            }
        }, modifier = Modifier.fillMaxSize())

        Canvas(modifier = Modifier.fillMaxSize()) {
            val targetRatio = 2.5f / 3.5f
            var boxW = size.width * 0.78f
            var boxH = boxW / targetRatio
            if (boxH > size.height * 0.56f) {
                boxH = size.height * 0.56f
                boxW = boxH * targetRatio
            }
            val left = (size.width - boxW) / 2f
            val top = (size.height - boxH) / 2f - size.height * 0.03f

            drawRoundRect(
                color = if (lockedCardIdRef.value.isNotEmpty()) AccentTeal else Color.White.copy(alpha = 0.88f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(28f, 28f),
                style = Stroke(width = 7f)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(color = Color.Black.copy(0.86f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть", tint = Color.White)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            Text(
                                text = scopeSet?.name ?: "Все сеты (нужен номер XXX/YYY)",
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
                            Icon(
                                if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                null,
                                tint = if (torch) Color(0xFFFFD54F) else Color.White
                            )
                        }
                        IconButton(
                            onClick = {
                                extraRot = (extraRot + 90) % 360
                                resetDuplicateLock()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.RotateRight, null, tint = Color.White)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "Normal" to "Normal",
                            "Reverse Holofoil" to "Reverse",
                            "Holofoil" to "Holo"
                        ).forEach { (finishKey, labelText) ->
                            FilterChip(
                                selected = selectedBatchFinish == finishKey,
                                onClick = { selectedBatchFinish = finishKey },
                                label = { Text(labelText, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            bannerText?.let {
                Spacer(Modifier.height(6.dp))
                Surface(color = AccentTeal, shape = RoundedCornerShape(20.dp)) {
                    Text(
                        it,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(14.dp, 5.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { resetDuplicateLock() },
                    colors = ButtonDefaults.buttonColors(containerColor = CardSurface),
                    border = BorderStroke(1.dp, BorderGray),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("Следующая", color = Color.White, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        if (pendingBatch.isNotEmpty()) {
                            pendingBatch = pendingBatch.dropLast(1)
                            resetDuplicateLock()
                        }
                    },
                    enabled = pendingBatch.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = CardSurface),
                    border = BorderStroke(1.dp, BorderGray),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("Отменить последнюю", color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (pendingBatch.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface.copy(alpha = 0.94f)),
                    border = BorderStroke(1.dp, BorderGray),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp)) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 135.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(pendingBatch, key = { _, item -> item.uid }) { _, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BgBlack.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${item.slot.name} • ${formatCardNumber(item.slot.localId, item.slot.setTotal)}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (item.slot.finish == "Reverse Holofoil") "Reverse" else item.slot.finish,
                                        color = AccentTeal,
                                        fontSize = 11.sp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (item.needsCheck) "проверить" else "ок",
                                        color = if (item.needsCheck) Color(0xFFFFB74D) else Color(0xFF81C784),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { pendingBatch = pendingBatch.filterNot { it.uid == item.uid } },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Убрать", tint = TextMuted, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Button(
                            onClick = {
                                val toCommit = pendingBatch
                                toCommit.forEach { onIncrementSlot(it.slot.slotId) }
                                pendingBatch = emptyList()
                                resetDuplicateLock()
                                beepAndVib()
                                bannerText = "Добавлено в библиотеку: ${toCommit.size}"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text(
                                text = "Добавить в библиотеку (${pendingBatch.size})",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
