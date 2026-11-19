package com.ncorti.kotlin.template.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
data class CollectedCard(val id: String, val name: String, val number: String, val setName: String, val imageUrl: String, val count: Int, val finish: String? = "Normal")
data class ParsedCardInfo(val queries: List<String>, val displayLabel: String, val hasExactNumber: Boolean)

interface PokemonTcgApi {
    @GET("v2/cards")
    suspend fun searchCards(@Query("q") q: String, @Query("pageSize") ps: Int = 1, @Query("select") s: String = "id,name,number,set,images"): ApiResponse
}

object ApiClient {
    val api: PokemonTcgApi by lazy { Retrofit.Builder().baseUrl("https://api.pokemontcg.io/").addConverterFactory(GsonConverterFactory.create()).build().create(PokemonTcgApi::class.java) }
    val cache = ConcurrentHashMap<String, PokemonCardDto>()
}

val SET_CODE_MAP = mapOf(
    "SVI" to "sv1", "PAL" to "sv2", "OBF" to "sv3", "MEW" to "sv3pt5",
    "PAR" to "sv4", "PAF" to "sv4pt5", "TEF" to "sv5", "TWM" to "sv6",
    "SFA" to "sv6pt5", "SCR" to "sv7", "SSP" to "sv8", "PRE" to "sv8pt5",
    "JTG" to "sv9", "DRI" to "sv10", "BLK" to "zsv10pt5", "WHT" to "rsv10pt5",
    "SVP" to "svp", "SVE" to "sve",
    "SSH" to "swsh1", "RCL" to "swsh2", "DAA" to "swsh3", "CPA" to "swsh35",
    "VIV" to "swsh4", "SHF" to "swsh45", "BST" to "swsh5", "CRE" to "swsh6",
    "EVS" to "swsh7", "CEL" to "cel25", "FST" to "swsh8", "BRS" to "swsh9",
    "ASR" to "swsh10", "PGO" to "pgo", "LOR" to "swsh11", "SIT" to "swsh12",
    "CRZ" to "swsh12pt5"
)

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    fun addC(c: PokemonCardDto, tf: String?, finish: String) {
        val set = tf ?: c.set.name; val eid = "${c.id}::$set::$finish"; val ex = cards.find { it.id == eid }
        upd(if (ex != null) cards.map { if (it.id == eid) it.copy(count = it.count + 1) else it } else listOf(CollectedCard(eid, c.name, c.number, set, c.images.small, 1, finish)) + cards)
    }
    fun movC(cid: String, nf: String) {
        val t = cards.find { it.id == cid } ?: return; if (t.setName == nf) return
        val fin = t.finish ?: "Normal"
        val nid = "${t.id.substringBefore("::")}::$nf::$fin"; val ex = cards.find { it.id == nid }
        upd(if (ex != null) cards.filter { it.id != cid }.map { if (it.id == nid) it.copy(count = it.count + t.count) else it } else cards.map { if (it.id == cid) it.copy(id = nid, setName = nf) else it })
    }
    fun chgFin(cid: String) {
        val t = cards.find { it.id == cid } ?: return
        val order = listOf("Normal", "Reverse", "Holo")
        val nextFin = order[(order.indexOf(t.finish ?: "Normal") + 1) % order.size]
        val nid = "${t.id.substringBefore("::")}::${t.setName}::$nextFin"; val ex = cards.find { it.id == nid }
        upd(if (ex != null) cards.filter { it.id != cid }.map { if (it.id == nid) it.copy(count = it.count + t.count) else it } else cards.map { if (it.id == cid) it.copy(id = nid, finish = nextFin) else it })
    }

    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.QrCodeScanner, null) }, label = { Text("Сканер ∞") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.CollectionsBookmark, null) }, label = { Text("Коллекция (${cards.sumOf { it.count }})") })
        }
    }) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            if (tab == 0) ScannerScreen(folders, scanFolder, { scanFolder = it }, ::addF) { c, fin -> addC(c, scanFolder, fin) }
            else CollectionScreen(cards, folders, ::addF, ::delF, ::movC, ::chgFin,
                { id -> upd(cards.map { if (it.id == id) it.copy(count = it.count + 1) else it }) },
                { id -> upd(cards.mapNotNull { if (it.id != id) it else if (it.count > 1) it.copy(count = it.count - 1) else null }) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(folders: List<String>, activeFolder: String?, onSelFolder: (String?) -> Unit, onNewFolder: (String) -> Unit, onAdded: (PokemonCardDto, String) -> Unit) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current; val scope = rememberCoroutineScope()
    var perm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    LaunchedEffect(Unit) { if (!perm) launch.launch(Manifest.permission.CAMERA) }

    var status by remember { mutableStateOf("Наведите камеру на карту...") }
    var found by remember { mutableStateOf<PokemonCardDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastQ by remember { mutableStateOf("") }; var lastQT by remember { mutableLongStateOf(0L) }
    var auto by remember { mutableStateOf(true) }
    var finishMode by remember { mutableStateOf("Normal") }
    var locked by remember { mutableStateOf(false) }
    var emptyFrames by remember { mutableIntStateOf(0) }
    var manualTrigger by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var dlg by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var extraRot by remember { mutableIntStateOf(0) }
    var camCtrl by remember { mutableStateOf<Camera?>(null) }

    val curFolder by rememberUpdatedState(activeFolder)
    val curAuto by rememberUpdatedState(auto)
    val curFinishMode by rememberUpdatedState(finishMode)
    val curExtraRot by rememberUpdatedState(extraRot)
    val tone = remember { try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 95) } catch (_: Exception) { null } }
    DisposableEffect(Unit) { onDispose { tone?.release() } }

    fun beepAndVib() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 130) } catch (_: Exception) {}
        try { if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator.vibrate(VibrationEffect.createOneShot(60, -1)) else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(60) } catch (_: Exception) {}
    }

    LaunchedEffect(torch, camCtrl) { try { camCtrl?.cameraControl?.enableTorch(torch) } catch (_: Exception) {} }
    LaunchedEffect(banner) { if (banner != null) { delay(1300); banner = null } }
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
                        if (!manualTrigger && now - lastT < 400) { px.close(); return@setAnalyzer }
                        @androidx.camera.core.ExperimentalGetImage val img = px.image
                        if (img != null) {
                            lastT = now
                            val rot = (px.imageInfo.rotationDegrees + curExtraRot) % 360
                            val inImg = InputImage.fromMediaImage(img, rot)
                            rec.process(inImg).addOnSuccessListener { v ->
                                val isManual = manualTrigger
                                val p = parseCardClean(v, allowNameOnly = isManual); val t = System.currentTimeMillis()
                                if (p == null) {
                                    if (isManual) {
                                        manualTrigger = false
                                        status = "Номер/код сета не видны"
                                    }
                                    emptyFrames++
                                    if (emptyFrames >= 3 && locked) {
                                        locked = false
                                        lastQ = ""
                                        if (!busy) status = "Готов к следующей карте..."
                                    }
                                    return@addOnSuccessListener
                                }
                                emptyFrames = 0
                                if (!isManual && locked && curAuto) return@addOnSuccessListener
                                val key = p.queries.firstOrNull() ?: return@addOnSuccessListener
                                if (!busy && (isManual || key != lastQ || t - lastQT > 2000)) {
                                    manualTrigger = false
                                    lastQ = key; lastQT = t; status = "Ищу: ${p.displayLabel}"; busy = true
                                    scope.launch {
                                        try {
                                            val card = ApiClient.cache[key] ?: withContext(Dispatchers.IO) {
                                                p.queries.firstNotNullOfOrNull { q -> ApiClient.api.searchCards(q).data.firstOrNull() }?.also { ApiClient.cache[key] = it }
                                            }
                                            if (card != null) {
                                                val fin = curFinishMode
                                                found = card
                                                if (isManual || (curAuto && p.hasExactNumber && !locked)) {
                                                    locked = true
                                                    onAdded(card, fin); beepAndVib()
                                                    status = "Готово: ${card.name} [$fin]"
                                                    banner = "+1 ${card.name} [$fin] → ${curFolder ?: card.set.name}"
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
                    try {
                        prov.unbindAll()
                        camCtrl = prov.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(c))
            }
        }, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color.Black.copy(0.84f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton({ torch = !torch }, Modifier.size(32.dp)) {
                            Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, null, tint = if (torch) Color(0xFFFFD54F) else Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton({ extraRot = (extraRot + 90) % 360; lastQ = ""; locked = false; status = "Поворот: $extraRot°" }, Modifier.size(32.dp)) {
                            Icon(Icons.Default.RotateRight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Switch(auto, { auto = it; if (!it) locked = false })
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("Normal", "Reverse", "Holo")) { m ->
                            FilterChip(finishMode == m, { finishMode = m }, { Text(m, fontSize = 11.sp) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(activeFolder == null, { onSelFolder(null) }, { Text("Родной сет", fontSize = 11.sp) }) }
                        items(folders) { f -> FilterChip(activeFolder == f, { onSelFolder(f) }, { Text(f, fontSize = 11.sp) }) }
                        item { AssistChip({ dlg = true }, { Text("+ Папка", fontSize = 11.sp) }) }
                    }
                }
            }
            banner?.let { Spacer(Modifier.height(6.dp)); Surface(color = Color(0xFF2E7D32), shape = RoundedCornerShape(20.dp)) { Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 6.dp)) } }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            found?.let { c ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(c.images.small, c.name, Modifier.size(60.dp, 84.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${c.name} [$finishMode]", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${activeFolder ?: c.set.name} • #${c.number}", fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ onAdded(c, finishMode); beepAndVib(); banner = "+1 ${c.name} [$finishMode]" }, Modifier.weight(1f)) { Text("+1", fontSize = 12.sp) }
                                OutlinedButton({ found = null; lastQ = ""; locked = false; status = "Наведите камеру..." }) { Text("Сброс", fontSize = 12.sp) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            FilledTonalButton(
                onClick = {
                    locked = false
                    lastQ = ""
                    manualTrigger = true
                    status = "Ручной снимок..."
                },
                shape = CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.Black.copy(0.85f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Скан сейчас", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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

fun parseCardClean(vt: Text, allowNameOnly: Boolean = false): ParsedCardInfo? {
    val raw = vt.text; if (raw.isBlank()) return null
    var num: String? = null
    var total: String? = null
    var setCode: String? = null

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

        // Ищем 3-буквенный код сета перед дробью (например, WHT, MEW, SVI, OBF, PAL, PAR)
        val beforeSlash = raw.substring(0, slashMatch.range.first).takeLast(28)
        val codeCandidates = Regex("""\b([A-Z0-9]{3})\b""").findAll(beforeSlash.uppercase()).map { it.groupValues[1] }.toList()
        for (cand in codeCandidates.asReversed()) {
            val fixedCode = cand.replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B')
            if (SET_CODE_MAP.containsKey(fixedCode)) {
                setCode = fixedCode
                break
            }
        }
    }

    // Если код сета не нашелся прямо перед дробью — ищем его в последних строках карты (левый нижний угол)
    if (setCode == null) {
        val tailText = raw.takeLast(140).uppercase()
        for (m in Regex("""\b([A-Z0-9]{3})\b""").findAll(tailText)) {
            val fixedCode = m.groupValues[1].replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B')
            if (SET_CODE_MAP.containsKey(fixedCode)) {
                setCode = fixedCode
                break
            }
        }
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
        t = t.replace(Regex("""(?<=[a-zA-Z]{3})\d{2,3}$"""), "").trim()
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

    val apiSetId = setCode?.let { SET_CODE_MAP[it] }
    val qs = mutableListOf<String>()

    // 1. Точный поиск по коду сета + номеру (работает даже если имя не прочиталось!)
    if (apiSetId != null && num != null) {
        qs.add("set.id:$apiSetId number:$num")
    }
    // 2. Поиск по имени + номеру + общему числу карт в сете
    if (name != null && num != null && total != null) {
        qs.add("name:\"$name\" number:$num set.printedTotal:$total")
    }
    // 3. Поиск по имени + номеру
    if (name != null && num != null) {
        qs.add("name:\"$name\" number:$num")
    }
    // 4. Запасной поиск только по имени — только при ручном нажатии
    if (qs.isEmpty()) {
        if (!allowNameOnly || name == null) return null
        qs.add("name:\"$name\"")
    }

    val label = buildString {
        if (setCode != null) append("$setCode ")
        if (name != null) append("$name ")
        if (num != null) append("#$num" + (total?.let { "/$it" } ?: ""))
    }.trim()

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
fun CollectionScreen(cards: List<CollectedCard>, folders: List<String>, onNewF: (String) -> Unit, onDelF: (String) -> Unit, onMove: (String, String) -> Unit, onChgFin: (String) -> Unit, onInc: (String) -> Unit, onDec: (String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }; var set by remember { mutableStateOf("Все") }
    var dlg by remember { mutableStateOf(false) }; var mov by remember { mutableStateOf<CollectedCard?>(null) }
    val sets = remember(cards, folders) { listOf("Все") + (folders + cards.map { it.setName }).distinct().sorted() }
    LaunchedEffect(sets) { if (!sets.contains(set)) set = "Все" }
    val list = remember(cards, q, set) { cards.filter { (set == "Все" || it.setName == set) && (q.isBlank() || it.name.contains(q, true) || it.number.contains(q, true) || it.setName.contains(q, true) || (it.finish ?: "").contains(q, true)) } }

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
                IconButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, list.joinToString("\n") { "${it.count}x ${it.name} [${it.finish ?: "Normal"}] (${it.setName} #${it.number})" }); type = "text/plain" }, "Экспорт")) }, enabled = list.isNotEmpty()) { Icon(Icons.Default.Share, null) }
            }
        }
        Spacer(Modifier.height(8.dp)); OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Поиск (имя, сет, Reverse)...") }, singleLine = true); Spacer(Modifier.height(8.dp))
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
                val fin = c.finish ?: "Normal"
                val finColor = when (fin) {
                    "Holo" -> Color(0xFFFFB300)
                    "Reverse" -> Color(0xFF26C6DA)
                    else -> Color(0xFF616161)
                }
                Card(shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        Box {
                            AsyncImage(c.imageUrl, c.name, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(8.dp, 4.dp)) { Text("x${c.count}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                                    .background(finColor.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
                                    .clickable { onChgFin(c.id) }
                                    .padding(6.dp, 2.dp)
                            ) { Text(fin, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
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
