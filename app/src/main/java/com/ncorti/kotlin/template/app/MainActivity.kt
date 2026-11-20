package com.ncorti.kotlin.template.app

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    var activeSet by remember { mutableStateOf<TcgSet?>(null) }
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
            },
            onIncrement = { slotId -> changeQty(slotId, 1) },
            onDecrement = { slotId -> changeQty(slotId, -1) },
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
            onDecrementSlot = { slotId -> changeQty(slotId, -1) }
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
    onDecrementSlot: (String) -> Unit
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
            modifier = Modifier.fillMaxWidth()
        )

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
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortAscending by remember { mutableStateOf(true) }
    var filterMode by remember { mutableStateOf("ALL") }
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
            Spacer(Modifier.width(4.dp))
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
            Text(
                text = set.name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
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
