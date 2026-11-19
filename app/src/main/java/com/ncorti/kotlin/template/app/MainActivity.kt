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
data class ParsedCardInfo(val queries: List<String>, val displayLabel: String, val hasExactNumber: Boolean)

interface PokemonTcgApi {
    @GET("v2/cards")
    suspend fun searchCards(@Query("q") q: String, @Query("pageSize") ps: Int = 1, @Query("select") s: String = "id,name,number,set,images"): ApiResponse
}

object ApiClient {
    val api: PokemonTcgApi by lazy { Retrofit.Builder().baseUrl("https://api.pokemontcg.io/").addConverterFactory(GsonConverterFactory.create()).build().create(PokemonTcgApi::class.java) }
    val cache = ConcurrentHashMap<String, PokemonCardDto>()
}

class CollectionRepository(ctx: Context) {
    private val cf = File(ctx.filesDir, "pokemon_collection.json")
    private val ff = File(ctx.filesDir, "custom_folders.json")
    private val gson = Gson()
    fun loadCards(): List<CollectedCard> = try { if (!cf.exists()) emptyList() else gson.fromJson(cf.readText(), object : TypeToken<List<CollectedCard>>() {}.type) ?: emptyList() } catch (_: Exception) { emptyList() }
    fun saveCards(c: List<CollectedCard>) = cf.writeText(gson.toJson(c))
    fun loadFolders(): List<String> = try { if (!ff.exists()) emptyList() else gson.fromJson(ff.readText(), object : TypeToken<List<String>>() {}.type) ?: emptyList() } catch (_: Exception) { emptyList() }
    fun saveFolders(f: List<String>) = ff.writeText(gson.toJson(f))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PokeCollectorApp() } }
    }
}

@Composable
fun PokeCollectorApp() {
    val ctx = LocalContext.current
    val repo = remember { CollectionRepository(ctx) }
    var cards by remember { mutableStateOf(repo.loadCards()) }
    var folders by remember { mutableStateOf(repo.loadFolders()) }
    var scanFolder by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    fun upd(l: List<CollectedCard>) { cards = l; repo.saveCards(l) }
    fun addF(n: String) { val c = n.trim(); if (c.isNotEmpty() && !folders.contains(c)) { folders = folders + c; repo.saveFolders(folders) } }
    fun delF(n: String) { folders = folders.filter { it != n }; repo.saveFolders(folders); if (scanFolder == n) scanFolder = null }
    fun addC(c: PokemonCardDto, tf: String?) {
        val set = tf ?: c.set.name; val eid = "${c.id}::$set"; val ex = cards.find { it.id == eid }
        upd(if (ex != null) cards.map { if (it.id == eid) it.copy(count = it.count + 1) else it } else listOf(CollectedCard(eid, c.name, c.number, set, c.images.small, 1)) + cards)
    }
    fun movC(cid: String, nf: String) {
        val t = cards.find { it.id == cid } ?: return; if (t.setName == nf) return
        val nid = "${t.id.substringBefore("::")}::$nf"; val ex = cards.find { it.id == nid }
        upd(if (ex != null) cards.filter { it.id != cid }.map { if (it.id == nid) it.copy(count = it.count + t.count) else it } else cards.map { if (it.id == cid) it.copy(id = nid, setName = nf) else it })
    }

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.QrCodeScanner, null) }, label = { Text("Сканер ∞") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.CollectionsBookmark, null) }, label = { Text("Коллекция (${cards.sumOf { it.count }})") })
        }
    }) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            if (tab == 0) ScannerScreen(folders, scanFolder, { scanFolder = it }, ::addF) { addC(it, scanFolder) }
            else CollectionScreen(cards, folders, ::addF, ::delF, ::movC,
                { id -> upd(cards.map { if (it.id == id) it.copy(count = it.count + 1) else it }) },
                { id -> upd(cards.mapNotNull { if (it.id != id) it else if (it.count > 1) it.copy(count = it.count - 1) else null }) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(folders: List<String>, activeFolder: String?, onSelFolder: (String?) -> Unit, onNewFolder: (String) -> Unit, onAdded: (PokemonCardDto) -> Unit) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current; val scope = rememberCoroutineScope()
    var perm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    LaunchedEffect(Unit) { if (!perm) launch.launch(Manifest.permission.CAMERA) }

    var status by remember { mutableStateOf("Жду карту...") }
    var found by remember { mutableStateOf<PokemonCardDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastQ by remember { mutableStateOf("") }; var lastQT by remember { mutableLongStateOf(0L) }
    var auto by remember { mutableStateOf(true) }
    var lastId by remember { mutableStateOf("") }
    var emptyFrames by remember { mutableIntStateOf(0) }
    var banner by remember { mutableStateOf<String?>(null) }
    var dlg by remember { mutableStateOf(false) }
    val curFolder by rememberUpdatedState(activeFolder); val curAuto by rememberUpdatedState(auto)

    fun vib() { try { if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(VibrationEffect.createOneShot(60, -1)) else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(60) } catch (_: Exception) {} }
    LaunchedEffect(banner) { if (banner != null) { delay(1400); banner = null } }
    if (dlg) FolderDlg({ dlg = false }) { onNewFolder(it); onSelFolder(it.trim()); dlg = false }
    if (!perm) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button({ launch.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") } }; return }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c ->
            PreviewView(c).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }.also { pv ->
                ProcessCameraProvider.getInstance(c).addListener({
                    val prov = ProcessCameraProvider.getInstance(c).get()
                    val prev = Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }
                    val an = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    var lastT = 0L
                    an.setAnalyzer(Executors.newSingleThreadExecutor()) { px ->
                        val now = System.currentTimeMillis()
                        if (now - lastT < 400) { px.close(); return@setAnalyzer }
                        @androidx.camera.core.ExperimentalGetImage val img = px.image
                        if (img != null) {
                            lastT = now
                            rec.process(InputImage.fromMediaImage(img, px.imageInfo.rotationDegrees)).addOnSuccessListener { v ->
                                val p = parseCardClean(v); val t = System.currentTimeMillis()
                                if (p == null) {
                                    emptyFrames++
                                    if (emptyFrames >= 2 && lastId.isNotEmpty()) {
                                        lastId = ""
                                        lastQ = ""
                                        if (!busy) status = "Жду следующую карту..."
                                    }
                                    return@addOnSuccessListener
                                }
                                emptyFrames = 0
                                val key = p.queries.firstOrNull() ?: ""
                                if (!busy && (key != lastQ || t - lastQT > 2500)) {
                                    lastQ = key; lastQT = t; status = "Ищу: ${p.displayLabel}"; busy = true
                                    scope.launch {
                                        try {
                                            val card = ApiClient.cache[key] ?: withContext(Dispatchers.IO) {
                                                p.queries.firstNotNullOfOrNull { q -> ApiClient.api.searchCards(q).data.firstOrNull() }?.also { ApiClient.cache[key] = it }
                                            }
                                            if (card != null) {
                                                found = card
                                                if (curAuto && p.hasExactNumber && card.id != lastId) {
                                                    lastId = card.id
                                                    onAdded(card); vib()
                                                    status = "Записано: ${card.name} (уберите карту)"
                                                    banner = "+1 ${card.name} → ${curFolder ?: card.set.name}"
                                                } else if (card.id == lastId) {
                                                    status = "Уже записана: ${card.name} (уберите карту)"
                                                } else {
                                                    status = "Найдено: ${card.name} (${card.set.name} #${card.number})"
                                                }
                                            } else status = "Не найдено: ${p.displayLabel}"
                                        } catch (_: Exception) {} finally { busy = false }
                                    }
                                }
                            }.addOnCompleteListener { px.close() }
                        } else px.close()
                    }
                    try { prov.unbindAll(); prov.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, an) } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(c))
            }
        }, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color.Black.copy(0.8f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (auto) "Авто: ВКЛ" else "Авто: ВЫКЛ", color = if (auto) Color(0xFF4CAF50) else Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp)); Switch(auto, { auto = it })
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(activeFolder == null, { onSelFolder(null) }, { Text("Родной сет", fontSize = 11.sp) }) }
                        items(folders) { f -> FilterChip(activeFolder == f, { onSelFolder(f) }, { Text(f, fontSize = 11.sp) }) }
                        item { AssistChip({ dlg = true }, { Text("+ Папка", fontSize = 11.sp) }) }
                    }
                }
            }
            banner?.let { Spacer(Modifier.height(8.dp)); Surface(color = Color(0xFF2E7D32), shape = RoundedCornerShape(20.dp)) { Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 8.dp)) } }
        }

        found?.let { c ->
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(c.images.small, c.name, Modifier.size(72.dp, 100.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("В папку: ${activeFolder ?: c.set.name} • #${c.number}", fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ onAdded(c); vib(); banner = "+1 ${c.name} → ${activeFolder ?: c.set.name}" }, Modifier.weight(1f)) { Text("+1 вручную") }
                            OutlinedButton({ found = null; lastQ = ""; lastId = ""; status = "Жду карту..." }) { Text("Сброс") }
                        }
                    }
                }
            }
        }
    }
}

fun fixOcrDigits(s: String): String = s.map {
    when (it) {
        'o', 'O', 'Q', 'D' -> '0'
        'I', 'l', '|' -> '1'
        'Z', 'z' -> '2'
        'S', 's' -> '5'
        'G' -> '6'
        'B' -> '8'
        else -> it
    }
}.joinToString("")

fun parseCardClean(vt: Text): ParsedCardInfo? {
    val raw = vt.text; if (raw.isBlank()) return null
    var num: String? = null
    var total: String? = null

    val slashMatch = Regex("""\b([A-Za-z0-9|]{1,5})\s*/\s*([A-Za-z0-9|]{2,4})\b""").find(raw)
    if (slashMatch != null) {
        val leftRaw = slashMatch.groupValues[1]
        val rightFixed = fixOcrDigits(slashMatch.groupValues[2])
        val prefix = Regex("""^(TG|GG|SV|SWSH|SM|XY|BW|RC|SH)\d+""", RegexOption.IGNORE_CASE).find(leftRaw)
        num = if (prefix != null) {
            val p = leftRaw.takeWhile { it.isLetter() }.uppercase()
            val d = fixOcrDigits(leftRaw.drop(p.length)).trimStart('0').ifEmpty { "0" }
            "$p$d"
        } else {
            fixOcrDigits(leftRaw).filter { it.isDigit() }.trimStart('0').ifEmpty { "0" }
        }
        val totDigits = rightFixed.filter { it.isDigit() }.trimStart('0')
        if (totDigits.isNotEmpty() && totDigits.toIntOrNull() in 10..400) total = totDigits
    }

    val lines = vt.textBlocks.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }
    val badPrefix = Regex("""^(STAGE\s*\d*|STAG\w*|GTAG\w*|BASIC|BASI\w*|VMAX|VSTAR|TRAINER|SUPPORTER|ITEM|STADIUM|ENERGY)\s+""", RegexOption.IGNORE_CASE)
    val noise = setOf("BASIC", "STAGE", "STAGE 1", "STAGE 2", "VMAX", "VSTAR", "TRAINER", "SUPPORTER", "ITEM", "STADIUM", "ENERGY")
    var name: String? = null
    for (l in lines.take(8)) {
        var t = l.text.trim()
            .replace(badPrefix, "")
            .replace(Regex("""\bHP\s*\d+\b|\b\d+\s*HP\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^a-zA-Z0-9\s.'-]"""), "")
            .trim()
        val w = t.split(Regex("""\s+""")).toMutableList()
        while (w.size >= 2 && w[0].length in 2..6 && w[0].count { it.isUpperCase() } >= 2 && w[1][0].isUpperCase() && w[1].drop(1).any { it.isLowerCase() }) {
            w.removeAt(0)
        }
        t = w.joinToString(" ")
        val u = t.uppercase()
        if (t.length in 3..26 && !noise.contains(u) && !u.startsWith("EVOLVES") && !u.startsWith("STAG") && !u.startsWith("GTAG") && t.any { it.isLetter() }) {
            name = t
            break
        }
    }
    val n = name ?: return null
    val qs = mutableListOf<String>()
    if (num != null && total != null) qs.add("name:\"$n\" number:$num set.printedTotal:$total")
    if (num != null) qs.add("name:\"$n\" number:$num")
    qs.add("name:\"$n\"")
    val label = if (num != null) "$n #$num" + (total?.let { "/$it" } ?: "") else n
    return ParsedCardInfo(qs, label, num != null)
}

@Composable
fun FolderDlg(onDim: () -> Unit, onOk: (String) -> Unit) {
    var n by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDim, title = { Text("Новая папка") }, text = { OutlinedTextField(n, { n = it }, placeholder = { Text("Название...") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button({ onOk(n) }, enabled = n.trim().isNotEmpty()) { Text("Создать") } }, dismissButton = { TextButton(onDim) { Text("Отмена") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(cards: List<CollectedCard>, folders: List<String>, onNewF: (String) -> Unit, onDelF: (String) -> Unit, onMove: (String, String) -> Unit, onInc: (String) -> Unit, onDec: (String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }; var set by remember { mutableStateOf("Все") }
    var dlg by remember { mutableStateOf(false) }; var mov by remember { mutableStateOf<CollectedCard?>(null) }
    val sets = remember(cards, folders) { listOf("Все") + (folders + cards.map { it.setName }).distinct().sorted() }
    LaunchedEffect(sets) { if (!sets.contains(set)) set = "Все" }
    val list = remember(cards, q, set) { cards.filter { (set == "Все" || it.setName == set) && (q.isBlank() || it.name.contains(q, true) || it.number.contains(q, true) || it.setName.contains(q, true)) } }

    if (dlg) FolderDlg({ dlg = false }) { onNewF(it); set = it.trim(); dlg = false }
    mov?.let { c ->
        val all = (folders + cards.map { it.setName }).distinct().sorted()
        AlertDialog(onDismissRequest = { mov = null }, title = { Text("В папку:") }, text = { Column { all.forEach { f -> TextButton({ onMove(c.id, f); mov = null }) { Text(f) } } } },
            confirmButton = { TextButton({ mov = null; dlg = true }) { Text("+ Новая") } }, dismissButton = { TextButton({ mov = null }) { Text("Закрыть") } })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column { Text("Моя коллекция", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Карт: ${list.sumOf { it.count }} (${list.size} уник.)", color = Color.Gray, fontSize = 13.sp) }
            Row {
                IconButton({ dlg = true }) { Icon(Icons.Default.CreateNewFolder, null) }
                IconButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, list.joinToString("\n") { "${it.count}x ${it.name} (${it.setName} #${it.number})" }); type = "text/plain" }, "Экспорт")) }, enabled = list.isNotEmpty()) { Icon(Icons.Default.Share, null) }
            }
        }
        Spacer(Modifier.height(8.dp)); OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Поиск...") }, singleLine = true); Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sets) { s ->
                val cnt = if (s == "Все") cards.sumOf { it.count } else cards.filter { it.setName == s }.sumOf { it.count }
                FilterChip(set == s, { set = s }, { Text("$s ($cnt)") }, trailingIcon = if (folders.contains(s) && cnt == 0) { { Icon(Icons.Default.Close, null, Modifier.size(16.dp).clickable { onDelF(s) }) } } else null)
            }
            item { AssistChip({ dlg = true }, { Text("+ Папка") }) }
        }
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Пусто", color = Color.Gray) }
        else LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { c ->
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Box {
                            AsyncImage(c.imageUrl, c.name, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(8.dp, 4.dp)) { Text("x${c.count}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        }
                        Spacer(Modifier.height(6.dp)); Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.setName} #${c.number}", fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { mov = c })
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                            FilledTonalIconButton({ onDec(c.id) }, Modifier.size(32.dp)) { Icon(Icons.Default.Remove, null, Modifier.size(16.dp)) }
                            Text("${c.count}", fontWeight = FontWeight.Bold)
                            FilledTonalIconButton({ onInc(c.id) }, Modifier.size(32.dp)) { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}
