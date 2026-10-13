package com.farsicaption.premium

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private val Ink = Color(0xFF08090D)
private val Panel = Color(0xFF12141C)
private val Panel2 = Color(0xFF1A1D28)
private val Gold = Color(0xFFFFCC66)
private val GoldSoft = Color(0xFFFFE2A6)
private val Mint = Color(0xFF83F5C8)
private val TextPrimary = Color(0xFFF8F7F2)
private val TextMuted = Color(0xFF9A9EAA)
private val Stroke = Color(0xFF2A2E3B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PremiumCaptionApp() }
    }
}

private fun saveProjectState(context: Context, uri: Uri?, captions: List<CaptionItem>) {
    val root = JSONObject().put("sourceUri", uri?.toString().orEmpty())
    val array = JSONArray()
    captions.forEach { caption ->
        array.put(JSONObject()
            .put("text", caption.text)
            .put("start", caption.start)
            .put("end", caption.end))
    }
    root.put("captions", array)
    context.getSharedPreferences("project_state", Context.MODE_PRIVATE)
        .edit().putString("active_project", root.toString()).apply()
}

private fun restoreProjectState(context: Context): Pair<Uri?, List<CaptionItem>> {
    val raw = context.getSharedPreferences("project_state", Context.MODE_PRIVATE)
        .getString("active_project", null) ?: return null to emptyList()
    return runCatching {
        val root = JSONObject(raw)
        val uri = root.optString("sourceUri").takeIf { it.isNotBlank() }?.let(Uri::parse)
        val array = root.optJSONArray("captions") ?: JSONArray()
        val captions = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(CaptionItem(item.optString("text"), item.optDouble("start"), item.optDouble("end")))
            }
        }
        uri to captions
    }.getOrElse { null to emptyList() }
}

@Composable
private fun PremiumCaptionApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LocalCaptionEngine(context) }

    // A fresh app launch always starts at Home. Persisted projects are never forced open.
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var captions by remember { mutableStateOf<List<CaptionItem>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var currentPosition by remember { mutableDoubleStateOf(0.0) }
    var seekTarget by remember { mutableStateOf<Double?>(null) }

    var isTranscribing by remember { mutableStateOf(context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE).getString(TranscriptionService.KEY_STATE, "") == TranscriptionService.STATE_RUNNING) }
    var isExporting by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableIntStateOf(if (engine.isModelReady()) 100 else 0) }
    var status by remember {
        mutableStateOf(
            if (engine.isModelReady()) "موتور فارسی آفلاین آماده است"
            else "برای اولین استفاده، موتور فارسی یک‌بار دانلود می‌شود"
        )
    }

    var maxWords by remember { mutableFloatStateOf(5f) }
    var preset by remember { mutableStateOf("luxury") }
    var fontSize by remember { mutableFloatStateOf(56f) }
    var marginV by remember { mutableFloatStateOf(120f) }
    var textColorKey by remember { mutableStateOf("cream") }
    var backgroundKey by remember { mutableStateOf("none") }

    LaunchedEffect(sourceUri) {
        while (sourceUri != null) {
            val prefs = context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE)
            val jobState = prefs.getString(TranscriptionService.KEY_STATE, "")
            isTranscribing = jobState == TranscriptionService.STATE_RUNNING
            status = prefs.getString(TranscriptionService.KEY_STATUS, status) ?: status
            if (jobState == TranscriptionService.STATE_DONE) {
                val array = JSONArray(prefs.getString(TranscriptionService.KEY_RESULT, "[]") ?: "[]")
                val restoredCaptions = buildList {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(CaptionItem(item.optString("text"), item.optDouble("start"), item.optDouble("end")))
                    }
                }
                if (restoredCaptions.isNotEmpty() && restoredCaptions != captions) {
                    captions = restoredCaptions
                    saveProjectState(context, sourceUri, restoredCaptions)
                }
            }
            delay(700)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // New media is a hard project boundary. Clear both the editor state and
            // any finished/running transcription payload from the previous video.
            context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.getSharedPreferences("project_state", Context.MODE_PRIVATE)
                .edit().remove("active_project").commit()
            sourceUri = uri
            captions = emptyList()
            saveProjectState(context, uri, emptyList())
            selectedIndex = 0
            currentPosition = 0.0
            status = "ویدیو جدید آماده است؛ برای ساخت زیرنویس، تشخیص گفتار را اجرا کن"
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                background = Ink,
                surface = Panel,
                primary = Gold,
                onPrimary = Ink,
                onBackground = TextPrimary,
                onSurface = TextPrimary,
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = Ink) {
                if (sourceUri == null) {
                    HomeScreen(
                        modelReady = engine.isModelReady(),
                        engineLabel = engine.preferredModelLabel(),
                        engineDownload = engine.preferredModelDownloadLabel(),
                        onPickVideo = { picker.launch(arrayOf("video/*")) },
                    )
                } else {
                    EditorScreen(
                        sourceUri = sourceUri!!,
                        captions = captions,
                        selectedIndex = selectedIndex.coerceIn(0, (captions.size - 1).coerceAtLeast(0)),
                        currentPosition = currentPosition,
                        seekTarget = seekTarget,
                        isTranscribing = isTranscribing,
                        isExporting = isExporting,
                        modelProgress = modelProgress,
                        status = status,
                        maxWords = maxWords,
                        preset = preset,
                        fontSize = fontSize,
                        marginV = marginV,
                        textColorKey = textColorKey,
                        backgroundKey = backgroundKey,
                        onPosition = { currentPosition = it },
                        onSeekConsumed = { seekTarget = null },
                        onBack = {
                            sourceUri = null
                            captions = emptyList()
                            context.getSharedPreferences("project_state", Context.MODE_PRIVATE).edit().remove("active_project").apply()
                        },
                        onNewVideo = { picker.launch(arrayOf("video/*")) },
                        onMaxWords = { maxWords = it },
                        onPreset = { preset = it },
                        onFontSize = { fontSize = it },
                        onMarginV = { marginV = it },
                        onTextColor = { textColorKey = it },
                        onBackground = { backgroundKey = it },
                        onSelectCaption = { index ->
                            selectedIndex = index
                            seekTarget = captions.getOrNull(index)?.start
                        },
                        onEditCaption = { index, text ->
                            captions = captions.toMutableList().also { list ->
                                val old = list[index]
                                list[index] = old.copy(text = text, words = emptyList())
                            }
                        },
                        onTranscribe = { language ->
                            val uri = sourceUri
                            if (uri != null && !isTranscribing) {
                                isTranscribing = true
                                status = "تشخیص گفتار در پس‌زمینه شروع شد"
                                val serviceIntent = Intent(context, TranscriptionService::class.java).apply {
                                    action = TranscriptionService.ACTION_START
                                    putExtra(TranscriptionService.EXTRA_URI, uri.toString())
                                    putExtra(TranscriptionService.EXTRA_MAX_WORDS, maxWords.roundToInt())
                                    putExtra(TranscriptionService.EXTRA_LANGUAGE, language)
                                }
                                ContextCompat.startForegroundService(context, serviceIntent)
                            }
                        },
                        onExport = {
                            val uri = sourceUri
                            if (uri != null && captions.isNotEmpty() && !isExporting) {
                                isExporting = true
                                scope.launch {
                                    captions
                                        .filter { it.originalText != it.text }
                                        .forEach { engine.learnCorrection(it.originalText, it.text) }

                                    runCatching {
                                        engine.export(
                                            uri = uri,
                                            captions = captions,
                                            preset = preset,
                                            fontSize = fontSize.roundToInt(),
                                            marginV = marginV.roundToInt(),
                                            textColorKey = textColorKey,
                                            backgroundKey = backgroundKey,
                                            onStatus = { status = it },
                                        )
                                    }.onSuccess { file ->
                                        val saved = withContext(Dispatchers.IO) {
                                            saveToGallery(context, file)
                                        }
                                        file.delete()
                                        status = if (saved) {
                                            "خروجی با موفقیت در گالری ذخیره شد"
                                        } else {
                                            "ویدیو ساخته شد اما ذخیره در گالری انجام نشد"
                                        }
                                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                    }.onFailure { e ->
                                        status = e.message ?: "خطا در خروجی"
                                    }
                                    isExporting = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modelReady: Boolean,
    engineLabel: String,
    engineDownload: String,
    onPickVideo: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF08090D), Color(0xFF10131B), Color(0xFF08090D))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = Gold.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Gold.copy(alpha = 0.32f)),
                ) {
                    Text(
                        "FA CAPTION AI",
                        color = GoldSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    color = if (modelReady) Mint.copy(alpha = 0.12f) else Gold.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        if (modelReady) "OFFLINE READY" else "NO TOKEN",
                        color = if (modelReady) Mint else GoldSoft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                "صدای تو،\nبا کپشن حرفه‌ای.",
                color = TextPrimary,
                fontSize = 38.sp,
                lineHeight = 45.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                "Auto Caption مخصوص فارسی؛ بدون App Token و بدون Backend. پردازش روی خود گوشی انجام می‌شود.",
                color = TextMuted,
                fontSize = 16.sp,
                lineHeight = 26.sp,
            )

            PremiumImportCard(onPickVideo)

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FeaturePill("Accuracy Max")
                FeaturePill("بدون توکن")
                FeaturePill("آفلاین")
                FeaturePill("RTL فارسی")
                FeaturePill("خروجی MP4")
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, Stroke),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Premium Engine • Adaptive Accuracy", color = GoldSoft, fontWeight = FontWeight.Bold)
                    EngineRow("01", engineLabel, "انتخاب خودکار بالاترین مدل پایدار • دانلود $engineDownload")
                    EngineRow("02", "Persian Clean Audio", "کاهش نویز + نرمال‌سازی صدا قبل از تشخیص")
                    EngineRow("03", "Self‑Learning Persian", "اصلاحات دستی را یاد می‌گیرد و دفعه بعد خودکار اعمال می‌کند")
                    EngineRow("04", "Luxury Editor", "Drag کپشن، رنگ، پس‌زمینه، اندازه و استایل زنده")
                }
            }
        }
    }
}

@Composable
private fun PremiumImportCard(onPickVideo: () -> Unit) {
    Card(
        onClick = onPickVideo,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171A24)),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Gold),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            Text("ویدیو را وارد کن", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("MP4 • MOV • WebM", color = TextMuted, fontSize = 13.sp)
            Button(
                onClick = onPickVideo,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("انتخاب ویدیو", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun FeaturePill(text: String) {
    Surface(
        color = Panel2,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Stroke),
    ) {
        Text(text, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun EngineRow(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Gold, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.width(34.dp))
        Column {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EditorScreen(
    sourceUri: Uri,
    captions: List<CaptionItem>,
    selectedIndex: Int,
    currentPosition: Double,
    seekTarget: Double?,
    isTranscribing: Boolean,
    isExporting: Boolean,
    modelProgress: Int,
    status: String,
    maxWords: Float,
    preset: String,
    fontSize: Float,
    marginV: Float,
    textColorKey: String,
    backgroundKey: String,
    onPosition: (Double) -> Unit,
    onSeekConsumed: () -> Unit,
    onBack: () -> Unit,
    onNewVideo: () -> Unit,
    onMaxWords: (Float) -> Unit,
    onPreset: (String) -> Unit,
    onFontSize: (Float) -> Unit,
    onMarginV: (Float) -> Unit,
    onTextColor: (String) -> Unit,
    onBackground: (String) -> Unit,
    onSelectCaption: (Int) -> Unit,
    onEditCaption: (Int, String) -> Unit,
    onTranscribe: (String) -> Unit,
    onExport: () -> Unit,
) {
    var tab by remember { mutableStateOf("text") }
    val activeCaption = captions.lastOrNull { currentPosition in it.start..it.end }
    val displayText = activeCaption?.text.orEmpty()

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(Ink)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ پروژه‌ها", color = TextMuted) }
                Spacer(Modifier.weight(1f))
                Text("Farsi Caption", color = TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Surface(
                    color = Mint.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        "OFFLINE",
                        color = Mint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        },
        bottomBar = {
            Surface(color = Panel, tonalElevation = 0.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onNewVideo,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Stroke),
                        modifier = Modifier.weight(0.38f),
                    ) {
                        Text("ویدیوی جدید", color = TextPrimary, fontSize = 12.sp)
                    }
                    Button(
                        onClick = onExport,
                        enabled = captions.isNotEmpty() && !isExporting,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(0.62f),
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("خروجی و ذخیره", fontWeight = FontWeight.Black)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            VideoStage(
                uri = sourceUri,
                captionText = displayText,
                preset = preset,
                fontSize = fontSize,
                marginV = marginV,
                textColorKey = textColorKey,
                backgroundKey = backgroundKey,
                seekTarget = seekTarget,
                onMarginV = onMarginV,
                onPosition = onPosition,
                onSeekConsumed = onSeekConsumed,
            )

            if (isTranscribing) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (modelProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { modelProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Gold,
                            trackColor = Panel2,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("دانلود موتور فارسی: $modelProgress٪", color = GoldSoft, fontSize = 12.sp)
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Gold,
                            trackColor = Panel2,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = TextMuted, fontSize = 12.sp)
                }
            } else {
                Text(status, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (captions.isEmpty()) {
                AutoCaptionPanel(maxWords, onMaxWords, onTranscribe)
            } else {
                TimelineStrip(captions, selectedIndex, currentPosition, onSelectCaption)
                EditorTabs(tab) { tab = it }

                when (tab) {
                    "text" -> TextEditor(captions, selectedIndex, onSelectCaption, onEditCaption)
                    "style" -> StyleEditor(
                        preset = preset,
                        fontSize = fontSize,
                        marginV = marginV,
                        textColorKey = textColorKey,
                        backgroundKey = backgroundKey,
                        onPreset = onPreset,
                        onFontSize = onFontSize,
                        onMarginV = onMarginV,
                        onTextColor = onTextColor,
                        onBackground = onBackground,
                    )
                    else -> TimingPanel(captions, selectedIndex)
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun VideoStage(
    uri: Uri,
    captionText: String,
    preset: String,
    fontSize: Float,
    marginV: Float,
    textColorKey: String,
    backgroundKey: String,
    seekTarget: Double?,
    onMarginV: (Float) -> Unit,
    onPosition: (Double) -> Unit,
    onSeekConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player) {
        while (true) {
            onPosition(player.currentPosition / 1000.0)
            delay(80)
        }
    }

    LaunchedEffect(seekTarget) {
        seekTarget?.let {
            player.seekTo((it * 1000).toLong())
            player.playWhenReady = true
            onSeekConsumed()
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .aspectRatio(9f / 14f),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (captionText.isNotBlank()) {
            val styleColor = captionTextColor(textColorKey)
            Surface(
                color = captionBackgroundColor(backgroundKey),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .pointerInput(marginV) {
                        detectDragGestures { _, dragAmount ->
                            onMarginV(
                                (marginV - dragAmount.y * 1.25f)
                                    .coerceIn(50f, 300f)
                            )
                        }
                    }
                    .padding(bottom = (34 + marginV / 6f).dp, start = 24.dp, end = 24.dp),
            ) {
                Text(
                    captionText,
                    color = styleColor,
                    fontSize = (fontSize / 3f).coerceIn(15f, 26f).sp,
                    lineHeight = (fontSize / 2.4f).coerceIn(20f, 32f).sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = if (preset == "glass") 7.dp else 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AutoCaptionPanel(
    maxWords: Float,
    onMaxWords: (Float) -> Unit,
    onTranscribe: (String) -> Unit,
) {
    var speechLanguage by remember { mutableStateOf("fa") }
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Auto Caption فارسی", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(
                "نسخه Ultra ابتدا صدا را برای گفتار بهینه می‌کند و روی گوشی‌های قوی از Large‑V3‑Turbo استفاده می‌کند؛ در صورت نیاز خودکار به Small برمی‌گردد. بدون Backend و بدون App Token.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            Text("کلمات در هر کپشن: ${maxWords.roundToInt()}", color = TextPrimary, fontSize = 13.sp)
            Slider(value = maxWords, onValueChange = onMaxWords, valueRange = 2f..7f, steps = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fa" to "فارسی", "ar" to "عربی", "auto" to "خودکار").forEach { (code, label) ->
                    OutlinedButton(onClick = { speechLanguage = code }) {
                        Text(if (speechLanguage == code) "✓ $label" else label)
                    }
                }
            }
            Button(
                onClick = { onTranscribe(speechLanguage) },
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink),
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ساخت زیرنویس فارسی", fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun TimelineStrip(
    captions: List<CaptionItem>,
    selectedIndex: Int,
    currentPosition: Double,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Timeline", color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(formatTime(currentPosition), color = GoldSoft, fontSize = 12.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            item { Spacer(Modifier.width(8.dp)) }
            itemsIndexed(captions) { index, caption ->
                val active = index == selectedIndex || currentPosition in caption.start..caption.end
                Surface(
                    color = if (active) Gold.copy(alpha = 0.16f) else Panel,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (active) Gold.copy(alpha = 0.55f) else Stroke),
                    modifier = Modifier
                        .width(150.dp)
                        .clickable { onSelect(index) },
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(caption.text, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("${formatTime(caption.start)} — ${formatTime(caption.end)}", color = TextMuted, fontSize = 10.sp)
                    }
                }
            }
            item { Spacer(Modifier.width(8.dp)) }
        }
    }
}

@Composable
private fun EditorTabs(tab: String, onTab: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Panel),
    ) {
        TabButton("متن", "text", tab, onTab, Modifier.weight(1f))
        TabButton("استایل", "style", tab, onTab, Modifier.weight(1f))
        TabButton("زمان", "timing", tab, onTab, Modifier.weight(1f))
    }
}

@Composable
private fun TabButton(
    label: String,
    key: String,
    selected: String,
    onTab: (String) -> Unit,
    modifier: Modifier,
) {
    val active = selected == key
    Box(
        modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Panel2 else Color.Transparent)
            .clickable { onTab(key) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) GoldSoft else TextMuted, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun TextEditor(
    captions: List<CaptionItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onEdit: (Int, String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ویرایش متن", color = TextPrimary, fontWeight = FontWeight.Bold)
        captions.take(60).forEachIndexed { index, caption ->
            val active = selectedIndex == index
            OutlinedTextField(
                value = caption.text,
                onValueChange = { onEdit(index, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(index) },
                label = { Text(formatTime(caption.start)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Gold,
                    unfocusedBorderColor = if (active) Gold.copy(alpha = 0.5f) else Stroke,
                    focusedLabelColor = GoldSoft,
                    unfocusedLabelColor = TextMuted,
                    cursorColor = Gold,
                ),
                shape = RoundedCornerShape(16.dp),
                minLines = 1,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun StyleEditor(
    preset: String,
    fontSize: Float,
    marginV: Float,
    textColorKey: String,
    backgroundKey: String,
    onPreset: (String) -> Unit,
    onFontSize: (Float) -> Unit,
    onMarginV: (Float) -> Unit,
    onTextColor: (String) -> Unit,
    onBackground: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("استایل کپشن", color = TextPrimary, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            itemsIndexed(
                listOf(
                    "luxury" to "Luxury",
                    "bold" to "Bold",
                    "minimal" to "Minimal",
                    "neon" to "Neon",
                    "glass" to "Glass",
                )
            ) { _, item ->
                val active = preset == item.first
                Surface(
                    color = if (active) Gold.copy(alpha = 0.16f) else Panel,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (active) Gold else Stroke),
                    modifier = Modifier.clickable { onPreset(item.first) },
                ) {
                    Text(
                        item.second,
                        color = if (active) GoldSoft else TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text("اندازه متن: ${fontSize.roundToInt()}", color = TextMuted, fontSize = 12.sp)
        Slider(value = fontSize, onValueChange = onFontSize, valueRange = 30f..90f)

        Text("رنگ متن", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            itemsIndexed(captionTextColors) { _, option ->
                CaptionColorChip(
                    label = option.label,
                    swatch = option.color,
                    selected = textColorKey == option.key,
                    onClick = { onTextColor(option.key) },
                )
            }
        }

        Text("پس‌زمینه متن", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            itemsIndexed(captionBackgrounds) { _, option ->
                CaptionColorChip(
                    label = option.label,
                    swatch = option.color,
                    selected = backgroundKey == option.key,
                    onClick = { onBackground(option.key) },
                    transparent = option.key == "none",
                )
            }
        }

        Text("فاصله از پایین: ${marginV.roundToInt()}", color = TextMuted, fontSize = 12.sp)
        Slider(value = marginV, onValueChange = onMarginV, valueRange = 50f..300f)
    }
}


private data class CaptionColorChoice(
    val key: String,
    val label: String,
    val color: Color,
)

private val captionTextColors = listOf(
    CaptionColorChoice("white", "سفید", Color.White),
    CaptionColorChoice("cream", "کرم", Color(0xFFFFE2A6)),
    CaptionColorChoice("yellow", "زرد", Color(0xFFFFE45C)),
    CaptionColorChoice("cyan", "آبی", Color(0xFF75E6FF)),
    CaptionColorChoice("mint", "سبز", Color(0xFF83F5C8)),
    CaptionColorChoice("red", "قرمز", Color(0xFFFF6B6B)),
    CaptionColorChoice("black", "مشکی", Color(0xFF111111)),
)

private val captionBackgrounds = listOf(
    CaptionColorChoice("none", "بدون", Color.Transparent),
    CaptionColorChoice("black60", "مشکی شفاف", Color.Black.copy(alpha = 0.60f)),
    CaptionColorChoice("black90", "مشکی", Color.Black.copy(alpha = 0.90f)),
    CaptionColorChoice("white80", "سفید", Color.White.copy(alpha = 0.82f)),
    CaptionColorChoice("gold80", "طلایی", Color(0xFFD6A83A).copy(alpha = 0.84f)),
    CaptionColorChoice("navy80", "سرمه‌ای", Color(0xFF101A35).copy(alpha = 0.86f)),
)

private fun captionTextColor(key: String): Color =
    captionTextColors.firstOrNull { it.key == key }?.color ?: GoldSoft

private fun captionBackgroundColor(key: String): Color =
    captionBackgrounds.firstOrNull { it.key == key }?.color ?: Color.Transparent

@Composable
private fun CaptionColorChip(
    label: String,
    swatch: Color,
    selected: Boolean,
    onClick: () -> Unit,
    transparent: Boolean = false,
) {
    Surface(
        color = if (selected) Gold.copy(alpha = 0.12f) else Panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) Gold else Stroke),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (transparent) Panel2 else swatch),
                contentAlignment = Alignment.Center,
            ) {
                if (transparent) {
                    Text("×", color = TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(label, color = if (selected) GoldSoft else TextPrimary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TimingPanel(captions: List<CaptionItem>, selectedIndex: Int) {
    val caption = captions.getOrNull(selectedIndex)
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("زمان‌بندی هوشمند", color = TextPrimary, fontWeight = FontWeight.Bold)
            if (caption != null) {
                Text("شروع: ${formatTime(caption.start)}", color = TextMuted)
                Text("پایان: ${formatTime(caption.end)}", color = TextMuted)
                Text("کلمات زمان‌بندی‌شده: ${caption.words.size}", color = GoldSoft)
            }
            Text(
                "نسخه آفلاین زمان قطعه گفتار را از Whisper می‌گیرد و کلمات را داخل همان بازه همگام می‌کند.",
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun saveToGallery(context: Context, source: File): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "FarsiCaption-${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FarsiCaption")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return false
            source.copyTo(File(dir, "FarsiCaption-${System.currentTimeMillis()}.mp4"), overwrite = true)
            true
        }
    } catch (_: Exception) {
        false
    }
}
