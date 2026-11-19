package com.ncorti.kotlin.template.app

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class ApiResponse(val data: List<PokemonCardDto>)
data class SetsApiResponse(val data: List<RemoteSetDto>)
data class RemoteSetDto(
    val id: String,
    val name: String,
    val series: String? = null,
    val ptcgoCode: String? = null,
    val total: Int? = null,
    val releaseDate: String? = null
)

data class PokemonCardDto(val id: String, val name: String, val number: String, val set: CardSetDto, val images: CardImagesDto)
data class CardSetDto(val id: String? = null, val name: String)
data class CardImagesDto(val small: String, val large: String)

data class CollectedCard(
    val id: String,
    val name: String,
    val number: String,
    val setName: String,
    val imageUrl: String,
    val count: Int,
    val finish: String? = "Normal"
)

data class HashedCard(
    val id: String,
    val name: String,
    val number: String,
    val setId: String,
    val setName: String,
    val imageUrl: String,
    val dHash: Long,
    val colorSig: IntArray
) {
    fun toDto() = PokemonCardDto(id, name, number, CardSetDto(setId, setName), CardImagesDto(imageUrl, imageUrl))
}

interface PokemonTcgApi {
    @GET("v2/cards")
    suspend fun getCardsBySet(
        @Query("q") q: String,
        @Query("pageSize") ps: Int = 50,
        @Query("page") page: Int = 1
    ): ApiResponse

    @GET("v2/sets")
    suspend fun getAllSets(
        @Query("pageSize") ps: Int = 250,
        @Query("page") page: Int = 1,
        @Query("orderBy") orderBy: String = "-releaseDate"
    ): SetsApiResponse
}

object ApiClient {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: PokemonTcgApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.pokemontcg.io/")
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PokemonTcgApi::class.java)
    }
}

// --- Visual Hashing Engine (dHash 64-bit + RGB Color Grid) ---
object VisualMatcher {
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
                if (l1 > l2) {
                    hash = hash or (1L shl bit)
                }
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

    fun findBestMatch(
        frameHash: Long,
        frameColor: IntArray,
        db: List<HashedCard>,
        activeSetFilter: String? = null
    ): Pair<HashedCard, Int>? {
        if (db.isEmpty()) return null
        val pool = if (activeSetFilter != null) {
            db.filter { it.setId == activeSetFilter }.ifEmpty { db }
        } else db

        var bestCard: HashedCard? = null
        var bestScore = Int.MAX_VALUE

        for (c in pool) {
            val hamming = java.lang.Long.bitCount(frameHash xor c.dHash)
            if (hamming > 22) continue

            var colorDiff = 0
            val cSig = c.colorSig
            if (cSig.size == 48) {
                for (i in 0 until 48) {
                    colorDiff += abs(frameColor[i] - cSig[i])
                }
            }
            val score = hamming * 35 + (colorDiff / 48)
            if (score < bestScore) {
                bestScore = score
                bestCard = c
            }
        }

        return if (bestCard != null && bestScore <= 620) bestCard to bestScore else null
    }
}

class CollectionRepository(ctx: Context) {
    private val cf = File(ctx.filesDir, "pokemon_collection.json")
    private val ff = File(ctx.filesDir, "custom_folders.json")
    private val hf = File(ctx.filesDir, "visual_hashes_v1.json")
    private val sf = File(ctx.filesDir, "all_sets_cache.json")
    private val gson = Gson()

    fun loadCards(): List<CollectedCard> = try {
        if (!cf.exists()) emptyList()
        else gson.fromJson(cf.readText(), object : TypeToken<List<CollectedCard>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun saveCards(c: List<CollectedCard>) = cf.writeText(gson.toJson(c))

    fun loadFolders(): List<String> = try {
        if (!ff.exists()) emptyList()
        else gson.fromJson(ff.readText(), object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun saveFolders(f: List<String>) = ff.writeText(gson.toJson(f))

    fun loadHashes(): List<HashedCard> = try {
        if (!hf.exists()) emptyList()
        else gson.fromJson(hf.readText(), object : TypeToken<List<HashedCard>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun saveHashes(h: List<HashedCard>) = hf.writeText(gson.toJson(h))

    fun loadSetsCache(): List<RemoteSetDto> = try {
        if (!sf.exists()) emptyList()
        else gson.fromJson(sf.readText(), object : TypeToken<List<RemoteSetDto>>() {}.type) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun saveSetsCache(s: List<RemoteSetDto>) = sf.writeText(gson.toJson(s))
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
    var visualDb by remember { mutableStateOf(repo.loadHashes()) }
    var allSets by remember { mutableStateOf(repo.loadSetsCache()) }
    var scanFolder by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            val fetched = withContext(Dispatchers.IO) {
                val res = mutableListOf<RemoteSetDto>()
                var p = 1
                while (true) {
                    val r = ApiClient.api.getAllSets(250, p)
                    res.addAll(r.data)
                    if (r.data.size < 250) break
                    p++
                }
                res
            }
            if (fetched.isNotEmpty()) {
                allSets = fetched
                repo.saveSetsCache(fetched)
            }
        } catch (_: Exception) {}
    }

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
            if (tab == 0) ScannerScreen(
                folders = folders,
                activeFolder = scanFolder,
                visualDb = visualDb,
                allSets = allSets,
                onSetsLoaded = { s -> allSets = s; repo.saveSetsCache(s) },
                onUpdateDb = { newDb -> visualDb = newDb; repo.saveHashes(newDb) },
                onSelFolder = { scanFolder = it },
                onNewFolder = ::addF,
                onAdded = { c, fin -> addC(c, scanFolder, fin) }
            )
            else CollectionScreen(cards, folders, ::addF, ::delF, ::movC, ::chgFin,
                { id -> upd(cards.map { if (it.id == id) it.copy(count = it.count + 1) else it }) },
                { id -> upd(cards.mapNotNull { if (it.id != id) it else if (it.count > 1) it.copy(count = it.count - 1) else null }) })
        }
    }
}

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

        // Увеличенная рамка под карту (пропорция 2.5 : 3.5)
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

suspend fun downloadAndHashSet(
    targetSet: RemoteSetDto,
    existingDb: List<HashedCard>,
    onProgress: (String) -> Unit
): List<HashedCard> = withContext(Dispatchers.IO) {
    val label = targetSet.ptcgoCode ?: targetSet.id.uppercase()
    onProgress("Загружаю список карт $label...")
    val allDtos = mutableListOf<PokemonCardDto>()

    try {
        var page = 1
        while (true) {
            var resp: ApiResponse? = null
            for (attempt in 1..3) {
                try {
                    resp = ApiClient.api.getCardsBySet("set.id:${targetSet.id}", 50, page)
                    break
                } catch (e: Exception) {
                    if (attempt == 3) throw e
                    delay(800)
                }
            }
            val list = resp?.data ?: emptyList()
            allDtos.addAll(list)
            onProgress("$label: получено ${allDtos.size} карт...")
            if (list.size < 50) break
            page++
        }
    } catch (_: Exception) {
        onProgress("$label: пробую резервный сервер TCGdex...")
        val req = Request.Builder().url("https://api.tcgdex.net/v2/en/sets/${targetSet.id}").build()
        ApiClient.okHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Сервер недоступен (${r.code})")
            val body = r.body?.string() ?: throw Exception("Пустой ответ")
            val json = JsonParser.parseString(body).asJsonObject
            val cardsArr = json.getAsJsonArray("cards") ?: throw Exception("Карты не найдены")
            for (el in cardsArr) {
                val obj = el.asJsonObject
                val cid = obj.get("id")?.asString ?: continue
                val cname = obj.get("name")?.asString ?: continue
                val localId = obj.get("localId")?.asString ?: ""
                val imgBase = obj.get("image")?.asString ?: continue
                val imgUrl = "$imgBase/low.webp"
                allDtos.add(
                    PokemonCardDto(
                        id = cid,
                        name = cname,
                        number = localId,
                        set = CardSetDto(targetSet.id, targetSet.name),
                        images = CardImagesDto(imgUrl, "$imgBase/high.webp")
                    )
                )
            }
        }
    }

    val existingIds = existingDb.map { it.id }.toSet()
    val toDownload = allDtos.filter { !existingIds.contains(it.id) }
    if (toDownload.isEmpty()) return@withContext existingDb

    val sem = Semaphore(8)
    var done = 0
    val newHashed = coroutineScope {
        toDownload.map { dto ->
            async {
                sem.withPermit {
                    try {
                        val req = Request.Builder().url(dto.images.small).build()
                        ApiClient.okHttp.newCall(req).execute().use { r ->
                            val bytes = r.body?.bytes()
                            if (bytes != null) {
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    val (dHash, colorSig) = VisualMatcher.computeSignature(bmp)
                                    bmp.recycle()
                                    done++
                                    if (done % 15 == 0 || done == toDownload.size) {
                                        onProgress("$label: $done / ${toDownload.size}")
                                    }
                                    return@withPermit HashedCard(
                                        id = dto.id,
                                        name = dto.name,
                                        number = dto.number,
                                        setId = targetSet.id,
                                        setName = dto.set.name,
                                        imageUrl = dto.images.small,
                                        dHash = dHash,
                                        colorSig = colorSig
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    existingDb + newHashed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    folders: List<String>,
    activeFolder: String?,
    visualDb: List<HashedCard>,
    allSets: List<RemoteSetDto>,
    onSetsLoaded: (List<RemoteSetDto>) -> Unit,
    onUpdateDb: (List<HashedCard>) -> Unit,
    onSelFolder: (String?) -> Unit,
    onNewFolder: (String) -> Unit,
    onAdded: (PokemonCardDto, String) -> Unit
) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current; val scope = rememberCoroutineScope()
    var perm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launch = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm = it }
    LaunchedEffect(Unit) { if (!perm) launch.launch(Manifest.permission.CAMERA) }

    var status by remember {
        mutableStateOf(
            if (visualDb.isEmpty()) "Нажмите [База] справа сверху и скачайте сет"
            else "Оффлайн-сканер готов (${visualDb.size} карт)"
        )
    }
    var matchInfo by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<PokemonCardDto?>(null) }
    var auto by remember { mutableStateOf(true) }
    var finishMode by remember { mutableStateOf("Normal") }
    var selectedSetFilter by remember { mutableStateOf<String?>(null) }
    var lastAddedCardId by remember { mutableStateOf("") }
    var lastMatchedHash by remember { mutableLongStateOf(0L) }
    var candidateId by remember { mutableStateOf("") }
    var candidateStreak by remember { mutableIntStateOf(0) }
    var manualTrigger by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    var dlg by remember { mutableStateOf(false) }
    var dbDlg by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var extraRot by remember { mutableIntStateOf(0) }
    var camCtrl by remember { mutableStateOf<Camera?>(null) }

    val curFolder by rememberUpdatedState(activeFolder)
    val curAuto by rememberUpdatedState(auto)
    val curDb by rememberUpdatedState(visualDb)
    val curSetFilter by rememberUpdatedState(selectedSetFilter)
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
    if (dbDlg) {
        VisualDbDialog(
            visualDb = visualDb,
            allSets = allSets,
            activeSetFilter = selectedSetFilter,
            onSetsLoaded = onSetsLoaded,
            onSelectSetFilter = { selectedSetFilter = it },
            onUpdateDb = { updated ->
                onUpdateDb(updated)
                status = "База обновлена (${updated.size} карт)"
            },
            onDismiss = { dbDlg = false }
        )
    }

    if (!perm) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button({ launch.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") } }; return }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c ->
            PreviewView(c).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }.also { pv ->
                ProcessCameraProvider.getInstance(c).addListener({
                    val prov = ProcessCameraProvider.getInstance(c).get()
                    val prev = Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }
                    val an = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    var lastT = 0L
                    an.setAnalyzer(Executors.newSingleThreadExecutor()) { px ->
                        val now = System.currentTimeMillis()
                        if (!manualTrigger && now - lastT < 180) { px.close(); return@setAnalyzer }
                        @androidx.camera.core.ExperimentalGetImage val img = px.image
                        if (img != null) {
                            lastT = now
                            val rot = (px.imageInfo.rotationDegrees + curExtraRot) % 360
                            val crop = extractCardCropBitmap(img, rot)
                            if (crop != null) {
                                val (frameHash, frameColor) = VisualMatcher.computeSignature(crop)
                                crop.recycle()

                                val db = curDb
                                val isManual = manualTrigger
                                manualTrigger = false

                                if (db.isEmpty()) {
                                    if (isManual) status = "Сначала скачайте сет в [База]"
                                } else {
                                    val match = VisualMatcher.findBestMatch(frameHash, frameColor, db, curSetFilter)
                                    if (match == null) {
                                        candidateId = ""
                                        candidateStreak = 0
                                        if (lastAddedCardId.isNotEmpty() && java.lang.Long.bitCount(frameHash xor lastMatchedHash) > 18) {
                                            lastAddedCardId = ""
                                            status = "Жду следующую карту..."
                                        } else if (isManual) {
                                            status = "Карта не узнана — выровняйте в рамке"
                                        }
                                    } else {
                                        val (card, score) = match
                                        matchInfo = "${card.name} (${card.setName} #${card.number}) • точность ${100 - (score / 10).coerceIn(1, 95)}%"

                                        if (card.id == candidateId) {
                                            candidateStreak++
                                        } else {
                                            candidateId = card.id
                                            candidateStreak = 1
                                        }

                                        if (isManual || (curAuto && candidateStreak >= 2 && card.id != lastAddedCardId)) {
                                            lastAddedCardId = card.id
                                            lastMatchedHash = frameHash
                                            val fin = curFinishMode
                                            val dto = card.toDto()
                                            scope.launch(Dispatchers.Main) {
                                                found = dto
                                                onAdded(dto, fin)
                                                beepAndVib()
                                                status = "Готово: ${card.name} [$fin]"
                                                banner = "+1 ${card.name} [$fin] → ${curFolder ?: card.setName}"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        px.close()
                    }
                    try {
                        prov.unbindAll()
                        camCtrl = prov.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, prev, an)
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(c))
            }
        }, modifier = Modifier.fillMaxSize())

        // Большая рамка под карту (пропорция 2.5 x 3.5)
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
                color = if (lastAddedCardId.isNotEmpty()) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.88f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(28f, 28f),
                style = Stroke(width = 7f)
            )
        }

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = Color.Black.copy(0.84f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(12.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(status, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (matchInfo.isNotEmpty()) {
                                Text(matchInfo, color = Color(0xFF81D4FA), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        FilledTonalButton(
                            onClick = { dbDlg = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("База (${visualDb.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton({ torch = !torch }, Modifier.size(32.dp)) {
                            Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, null, tint = if (torch) Color(0xFFFFD54F) else Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton({ extraRot = (extraRot + 90) % 360; lastAddedCardId = ""; status = "Поворот: $extraRot°" }, Modifier.size(32.dp)) {
                            Icon(Icons.Default.RotateRight, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Switch(auto, { auto = it; if (!it) lastAddedCardId = "" })
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
                                OutlinedButton({ found = null; lastAddedCardId = ""; candidateStreak = 0; status = "Жду карту..." }) { Text("Сброс", fontSize = 12.sp) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            FilledTonalButton(
                onClick = {
                    lastAddedCardId = ""
                    candidateStreak = 0
                    manualTrigger = true
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

@Composable
fun VisualDbDialog(
    visualDb: List<HashedCard>,
    allSets: List<RemoteSetDto>,
    activeSetFilter: String?,
    onSetsLoaded: (List<RemoteSetDto>) -> Unit,
    onSelectSetFilter: (String?) -> Unit,
    onUpdateDb: (List<HashedCard>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var loadingSetsList by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var searchSet by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (allSets.isEmpty()) {
            loadingSetsList = true
            try {
                val fetched = withContext(Dispatchers.IO) {
                    val res = mutableListOf<RemoteSetDto>()
                    var p = 1
                    while (true) {
                        val r = ApiClient.api.getAllSets(250, p)
                        res.addAll(r.data)
                        if (r.data.size < 250) break
                        p++
                    }
                    res
                }
                if (fetched.isNotEmpty()) onSetsLoaded(fetched)
            } catch (e: Exception) {
                progressText = "Ошибка загрузки сетов: ${e.message?.take(30) ?: "сеть"}"
            } finally {
                loadingSetsList = false
            }
        }
    }

    val filteredSets = remember(allSets, searchSet) {
        if (searchSet.isBlank()) allSets
        else allSets.filter {
            it.name.contains(searchSet, ignoreCase = true) ||
                (it.ptcgoCode ?: "").contains(searchSet, ignoreCase = true) ||
                it.id.contains(searchSet, ignoreCase = true) ||
                (it.series ?: "").contains(searchSet, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = { if (downloadingId == null) onDismiss() },
        title = {
            Column {
                Text("Все сеты (${allSets.size}) • В базе: ${visualDb.size}")
                if (progressText.isNotEmpty()) {
                    Text(progressText, fontSize = 12.sp, color = Color(0xFF81D4FA))
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = searchSet,
                    onValueChange = { searchSet = it },
                    placeholder = { Text("Поиск сета (WHT, 151, Base, Scarlet...)", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = activeSetFilter == null,
                        onClick = { onSelectSetFilter(null) },
                        label = { Text("Искать по всем (${visualDb.size})", fontSize = 11.sp) }
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (loadingSetsList && allSets.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filteredSets, key = { it.id }) { s ->
                            val countInDb = visualDb.count { it.setId == s.id }
                            val isCurrentFilter = activeSetFilter == s.id
                            val codeBadge = s.ptcgoCode ?: s.id.uppercase()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isCurrentFilter) Color(0xFF1E3A5F) else Color.White.copy(0.05f))
                                    .clickable { if (countInDb > 0) onSelectSetFilter(if (isCurrentFilter) null else s.id) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${s.name} ($codeBadge)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (countInDb > 0) "Скачано: $countInDb карт" + (if (isCurrentFilter) " • Фильтр" else "")
                                        else "${s.series ?: ""} • ${s.total ?: "?"} карт",
                                        fontSize = 11.sp,
                                        color = if (countInDb > 0) Color(0xFF81C784) else Color.Gray
                                    )
                                }
                                if (downloadingId == s.id) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(
                                        onClick = {
                                            downloadingId = s.id
                                            progressText = "Подключение к pokemontcg.io..."
                                            scope.launch {
                                                try {
                                                    val updated = downloadAndHashSet(s, visualDb) { progressText = it }
                                                    onUpdateDb(updated)
                                                    onSelectSetFilter(s.id)
                                                    progressText = "Готово: ${s.name}"
                                                } catch (e: Exception) {
                                                    progressText = "Ошибка сети: ${e.message?.take(30) ?: "сбой"}"
                                                } finally {
                                                    downloadingId = null
                                                }
                                            }
                                        },
                                        enabled = downloadingId == null
                                    ) {
                                        Text(if (countInDb > 0) "Обновить" else "Скачать", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = downloadingId == null) {
                Text("Закрыть")
            }
        }
    )
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
