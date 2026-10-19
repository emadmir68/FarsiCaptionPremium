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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PremiumCaptionApp() }
    }
}

private data class SavedProject(val uri: Uri?, val captions: List<CaptionItem>, val jobId: String?, val appliedJob: String?)
private fun saveProjectState(context: Context, uri: Uri?, captions: List<CaptionItem>, jobId: String?, appliedJob: String?) {
    if (uri == null) return
    val root = JSONObject().put("sourceUri", uri.toString()).put("captions", CaptionJson.encode(captions))
        .put("jobId", jobId).put("appliedJob", appliedJob)
    context.getSharedPreferences("project_state", Context.MODE_PRIVATE).edit()
        .putString("active_project", root.toString()).putString("project:$uri", root.toString()).apply()
}
private fun restoreProjectState(context: Context): SavedProject = runCatching {
    val root = JSONObject(context.getSharedPreferences("project_state", Context.MODE_PRIVATE).getString("active_project", "{}") ?: "{}")
    fun value(key: String) = root.optString(key).takeIf { it.isNotBlank() && it != "null" }
    SavedProject(value("sourceUri")?.let(Uri::parse), CaptionJson.decode(root.optJSONArray("captions") ?: JSONArray()), value("jobId"), value("appliedJob"))
}.getOrElse { SavedProject(null, emptyList(), null, null) }

@Composable
private fun PremiumCaptionApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LocalCaptionEngine(context) }

    // A fresh app launch always starts at Home. Persisted projects are never forced open.
    var sourceString by rememberSaveable { mutableStateOf<String?>(null) }
    val sourceUri = sourceString?.let(Uri::parse)
    var activeJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var appliedJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var captions by remember { mutableStateOf(if (sourceString != null) restoreProjectState(context).captions else emptyList()) }
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

    val editorSettings = remember { context.getSharedPreferences("editor_settings", Context.MODE_PRIVATE) }
    var maxWords by rememberSaveable { mutableFloatStateOf(editorSettings.getFloat("maxWords", 5f)) }
    var preset by rememberSaveable { mutableStateOf(editorSettings.getString("preset", "luxury") ?: "luxury") }
    var fontSize by rememberSaveable { mutableFloatStateOf(editorSettings.getFloat("fontSize", CaptionStyle.DEFAULT_FONT)) }
    var landscapeFontSize by rememberSaveable { mutableFloatStateOf(editorSettings.getFloat("landscapeFontSize", CaptionStyle.DEFAULT_LANDSCAPE_FONT)) }
    var marginV by rememberSaveable { mutableFloatStateOf(editorSettings.getFloat("marginV", 120f)) }
    var textColorKey by rememberSaveable { mutableStateOf(editorSettings.getString("textColor", "cream") ?: "cream") }
    var backgroundKey by rememberSaveable { mutableStateOf(editorSettings.getString("background", "none") ?: "none") }
    LaunchedEffect(maxWords, preset, fontSize, landscapeFontSize, marginV, textColorKey, backgroundKey) {
        editorSettings.edit().putFloat("maxWords", maxWords).putString("preset", preset)
            .putFloat("fontSize", fontSize).putFloat("landscapeFontSize", landscapeFontSize).putFloat("marginV", marginV)
            .putString("textColor", textColorKey).putString("background", backgroundKey).apply()
    }
    LaunchedEffect(sourceString, activeJobId) {
        val delivery = ResultDelivery(appliedJobId)
        while (sourceString != null) {
            val prefs = context.getSharedPreferences(TranscriptionService.PREFS, Context.MODE_PRIVATE)
            val identity = JobIdentity(activeJobId.orEmpty(), sourceString.orEmpty())
            val actualId = prefs.getString(TranscriptionService.KEY_JOB_ID, null)
            val actualUri = prefs.getString(TranscriptionService.KEY_URI, null)
            val matching = identity.matches(actualId, actualUri)
            val state = if (matching) prefs.getString(TranscriptionService.KEY_STATE, "") else ""
            isTranscribing = state == TranscriptionService.STATE_RUNNING
            if (matching) {
                status = prefs.getString(TranscriptionService.KEY_STATUS, status) ?: status
                modelProgress = prefs.getInt(TranscriptionService.KEY_PROGRESS, modelProgress)
            }
            if (delivery.consume(identity, actualId, actualUri, state == TranscriptionService.STATE_DONE)) {
                captions = CaptionJson.decode(JSONArray(prefs.getString(TranscriptionService.KEY_RESULT, "[]") ?: "[]"))
                appliedJobId = activeJobId
                saveProjectState(context, sourceUri, captions, activeJobId, appliedJobId)
            }
            delay(700)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            TranscriptionJobStore.detach(context)
            context.stopService(Intent(context, TranscriptionService::class.java))
            sourceString = uri.toString()
            activeJobId = null
            appliedJobId = null
            isTranscribing = false
            seekTarget = null
            captions = emptyList()
            saveProjectState(context, uri, captions, activeJobId, appliedJobId)
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
        StudioTheme {
            Surface(Modifier.fillMaxSize(), color = Ink) {
                if (sourceUri == null) {
                    HomeScreen(
                        modelReady = engine.isModelReady(),
                        engineLabel = engine.preferredModelLabel(),
                        engineDownload = engine.preferredModelDownloadLabel(),
                        onPickVideo = { picker.launch(arrayOf("video/*")) },
                        canResume = restoreProjectState(context).uri != null,
                        onResume = {
                            val saved = restoreProjectState(context)
                            sourceString = saved.uri?.toString()
                            captions = saved.captions
                            activeJobId = saved.jobId
                            appliedJobId = saved.appliedJob
                        },
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
                        landscapeFontSize = landscapeFontSize,
                        marginV = marginV,
                        textColorKey = textColorKey,
                        backgroundKey = backgroundKey,
                        onPosition = { currentPosition = it },
                        onSeekConsumed = { seekTarget = null },
                        onBack = {
                            saveProjectState(context, sourceUri, captions, activeJobId, appliedJobId)
                            sourceString = null
                            captions = emptyList()
                            isTranscribing = false
                        },
                        onNewVideo = { picker.launch(arrayOf("video/*")) },
                        onMaxWords = { maxWords = it },
                        onPreset = { preset = it },
                        onFontSize = { fontSize = it },
                        onLandscapeFontSize = { landscapeFontSize = it },
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
                            saveProjectState(context, sourceUri, captions, activeJobId, appliedJobId)
                        },
                        onTranscribe = { language, mode ->
                            val uri = sourceUri
                            if (uri != null && !isTranscribing) {
                                isTranscribing = true
                                status = "تشخیص گفتار در پس‌زمینه شروع شد"
                                val jobId = java.util.UUID.randomUUID().toString()
                                activeJobId = jobId
                                val identity = JobIdentity(jobId, uri.toString())
                                TranscriptionJobStore.start(context, identity)
                                saveProjectState(context, uri, captions, activeJobId, appliedJobId)
                                val serviceIntent = Intent(context, TranscriptionService::class.java).apply {
                                    action = TranscriptionService.ACTION_START
                                    putExtra(TranscriptionService.EXTRA_URI, uri.toString())
                                    putExtra(TranscriptionService.EXTRA_MAX_WORDS, maxWords.roundToInt())
                                    putExtra(TranscriptionService.EXTRA_LANGUAGE, language)
                                    putExtra(TranscriptionService.EXTRA_MODE, mode)
                                    putExtra(TranscriptionService.EXTRA_JOB_ID, jobId)
                                }
                                runCatching { ContextCompat.startForegroundService(context, serviceIntent) }.onFailure {
                                    isTranscribing = false
                                    TranscriptionJobStore.update(context, identity, TranscriptionService.STATE_ERROR, it.message ?: "شروع تشخیص گفتار ناموفق بود")
                                }
                            }
                        },
                        onCancel = {
                            context.startService(Intent(context, TranscriptionService::class.java).apply {
                                action = TranscriptionService.ACTION_CANCEL
                                putExtra(TranscriptionService.EXTRA_JOB_ID, activeJobId)
                            })
                        },
                        onExport = {
                            val uri = sourceUri
                            if (uri != null && captions.isNotEmpty() && !isExporting) {
                                isExporting = true
                                scope.launch {
                                    runCatching {
                                        engine.export(
                                            uri = uri,
                                            captions = captions,
                                            preset = preset,
                                            fontSize = fontSize.roundToInt(),
                                            landscapeFontSize = landscapeFontSize.roundToInt(),
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
    landscapeFontSize: Float,
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
    onLandscapeFontSize: (Float) -> Unit,
    onMarginV: (Float) -> Unit,
    onTextColor: (String) -> Unit,
    onBackground: (String) -> Unit,
    onSelectCaption: (Int) -> Unit,
    onEditCaption: (Int, String) -> Unit,
    onTranscribe: (String, String) -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("text") }
    val settings = LocalContext.current.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)
    var onlineMode by remember { mutableStateOf(settings.getBoolean("onlineMode", false)) }
    var showTranscription by rememberSaveable(sourceUri.toString()) { mutableStateOf(false) }
    var videoRatio by remember(sourceUri) { mutableFloatStateOf(9f / 16f) }
    val landscape = videoRatio > 1f
    val activeFont = if (landscape) landscapeFontSize else fontSize
    val setActiveFont = if (landscape) onLandscapeFontSize else onFontSize
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
                TextButton(onClick = onBack) { Text("خانه", color = TextMuted) }
                Spacer(Modifier.weight(1f))
                Text("استودیوی کپشن", color = TextPrimary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Surface(
                    color = Mint.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        if (onlineMode) "آنلاین Groq" else "آفلاین",
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
                        enabled = captions.isNotEmpty() && !isExporting && !isTranscribing,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
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
                fontSize = activeFont,
                videoRatio = videoRatio,
                onVideoRatio = { videoRatio = it },
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
                            color = Accent,
                            trackColor = Panel2,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(if (onlineMode) "پردازش آنلاین: $modelProgress٪" else "دانلود موتور فارسی: $modelProgress٪", color = AccentSoft, fontSize = 12.sp)
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Accent,
                            trackColor = Panel2,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = TextMuted, fontSize = 12.sp)
                    TextButton(onClick = onCancel) { Text("لغو تشخیص", color = AccentSoft) }
                }
            } else {
                Text(status, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }

            if (captions.isEmpty() || showTranscription) {
                AutoCaptionPanel(maxWords, onMaxWords, onTranscribe, enabled = !isTranscribing && !isExporting,
                    onOnlineMode = { onlineMode = it })
            }
            if (captions.isNotEmpty()) {
                TextButton(onClick = { showTranscription = !showTranscription }, enabled = !isTranscribing && !isExporting,
                    modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(if (showTranscription) "بستن تنظیمات تشخیص" else "تشخیص دوباره / انتخاب موتور", color = AccentSoft)
                }
                if (showTranscription) Text("زیرنویس فعلی تا آماده‌شدن نتیجهٔ جدید حفظ می‌شود.",
                    color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                TimelineStrip(captions, selectedIndex, currentPosition, onSelectCaption)
                EditorTabs(tab) { tab = it }

                when (tab) {
                    "text" -> TextEditor(captions, selectedIndex, onSelectCaption, onEditCaption)
                    "style" -> StyleEditor(
                        preset = preset,
                        fontSize = activeFont,
                        landscape = landscape,
                        marginV = marginV,
                        textColorKey = textColorKey,
                        backgroundKey = backgroundKey,
                        onPreset = onPreset,
                        onFontSize = setActiveFont,
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
    videoRatio: Float,
    onVideoRatio: (Float) -> Unit,
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

    val updateRatio by rememberUpdatedState(onVideoRatio)
    val density = LocalDensity.current
    val currentMargin by rememberUpdatedState(marginV)
    val captionFont = remember {
        FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.createFromAsset(context.assets, "fonts/Vazirmatn-Regular.ttf")))
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) updateRatio(size.width * size.pixelWidthHeightRatio / size.height)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
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

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
      val stageWidth = minOf(maxWidth, 360.dp * videoRatio.coerceIn(0.2f, 5f))
      BoxWithConstraints(Modifier.width(stageWidth).aspectRatio(videoRatio.coerceIn(0.2f, 5f)).clip(RoundedCornerShape(22.dp)).background(Color.Black)) {
        val previewHeightPx = with(density) { maxHeight.toPx() }
        val previewFontSize = with(density) { CaptionStyle.fontPixels(fontSize, with(density) { maxWidth.toPx() }, previewHeightPx).toSp() }
        val previewMargin = with(density) { CaptionStyle.marginPixels(marginV, previewHeightPx).toDp() }
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
            modifier = Modifier.fillMaxSize(),
        )
        if (captionText.isNotBlank()) Surface(
            color = captionBackgroundColor(backgroundKey), shape = RoundedCornerShape(4.dp),
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = previewMargin, start = 12.dp, end = 12.dp)
                .pointerInput(previewHeightPx) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onMarginV(CaptionStyle.dragMargin(currentMargin, drag.y, previewHeightPx))
                    }
                },
        ) {
            Text(captionText, color = captionTextColor(textColorKey), fontFamily = captionFont,
                fontSize = previewFontSize, lineHeight = previewFontSize * 1.25f,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black, Offset(1f, 1f), 2f)),
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
    }
  }
}

@Composable
internal fun AutoCaptionPanel(
    maxWords: Float,
    onMaxWords: (Float) -> Unit,
    onTranscribe: (String, String) -> Unit,
    enabled: Boolean = true,
    onOnlineMode: (Boolean) -> Unit = {},
) {
    val preferences = LocalContext.current.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)
    var speechLanguage by rememberSaveable { mutableStateOf(preferences.getString("speechLanguage", "fa") ?: "fa") }
    var mode by rememberSaveable { mutableStateOf(preferences.getString("modelMode", "quality") ?: "quality") }
    var online by rememberSaveable { mutableStateOf(preferences.getBoolean("onlineMode", false)) }
    var onlineModel by rememberSaveable { mutableStateOf(preferences.getString("groqMode", GroqMode.TURBO) ?: GroqMode.TURBO) }
    var keyReady by remember { mutableStateOf(false) }
    LaunchedEffect(online, onlineModel) {
        preferences.edit().putBoolean("onlineMode", online).putString("groqMode", onlineModel).apply()
        onOnlineMode(online)
    }
    LaunchedEffect(speechLanguage, mode) {
        preferences.edit().putString("speechLanguage", speechLanguage).putString("modelMode", mode).apply()
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Stroke),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("زیرنویس خودکار", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { online = false }, enabled = enabled) { Text(if (!online) "✓ آفلاین" else "آفلاین") }
                OutlinedButton(onClick = { online = true }, enabled = enabled) { Text(if (online) "✓ آنلاین سریع" else "آنلاین سریع") }
            }
            Text(
                if (online) "تشخیص گفتار روی سرور Groq انجام می‌شود؛ دانلود مدل لازم نیست."
                else "زبان و کیفیت را انتخاب کن. پردازش روی گوشی انجام می‌شود؛ مدل فقط بار اول دانلود می‌شود.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
            Text("کلمات در هر کپشن: ${maxWords.roundToInt()}", color = TextPrimary, fontSize = 13.sp)
            Slider(value = maxWords, onValueChange = onMaxWords, valueRange = 2f..7f, steps = 4, enabled = enabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fa" to "فارسی", "ar" to "عربی").forEach { (code, label) ->
                    OutlinedButton(onClick = { speechLanguage = code }, enabled = enabled) {
                        Text(if (speechLanguage == code) "✓ $label" else label)
                    }
                }
            }
            if (online) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(GroqMode.TURBO to "Turbo", GroqMode.QUALITY to "Large V3").forEach { (key, label) ->
                        OutlinedButton(onClick = { onlineModel = key }, enabled = enabled) { Text(if (onlineModel == key) "✓ $label" else label) }
                    }
                }
                GroqSettingsPanel(enabled) { keyReady = it }
            } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                val choices = if (speechLanguage == "fa")
                    listOf("persian" to "فارسی تخصصی", "quality" to "Turbo عمومی", "compact" to "Turbo کم‌حجم", "fast" to "Turbo سریع")
                else listOf("quality" to "کیفیت", "compact" to "Turbo کم‌حجم", "fast" to "Turbo سریع")
                val shownMode = if (speechLanguage == "ar" && mode == "persian") "quality" else mode
                choices.forEach { (key, label) ->
                    OutlinedButton(onClick = { mode = key }, enabled = enabled) { Text(if (shownMode == key) "✓ $label" else label) }
                }
            }
            if (!online && speechLanguage == "fa" && mode == "persian") Text(
                "مدل ویژهٔ فارسی؛ دریافت یک‌باره حدود ۵۴۸ مگابایت. برای مقایسه می‌توانی Turbo عمومی را انتخاب کنی.",
                color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp)
            if (!online && mode == "fast") Text("Turbo Compact با رمزگشایی سریع؛ برای کاهش محسوس زمان پردازش روی گوشی.", color = AccentSoft, fontSize = 12.sp)
            Button(
                onClick = { onTranscribe(speechLanguage, if (online) onlineModel else mode) },
                enabled = enabled && (!online || keyReady),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (online) "ارسال صدا و ساخت زیرنویس" else if (speechLanguage == "ar") "ساخت زیرنویس عربی" else "ساخت زیرنویس فارسی", fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 5.dp))
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
            Text("خط زمان", color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(formatTime(currentPosition), color = AccentSoft, fontSize = 12.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            item { Spacer(Modifier.width(8.dp)) }
            itemsIndexed(captions) { index, caption ->
                val active = index == selectedIndex || currentPosition in caption.start..caption.end
                Surface(
                    color = if (active) Accent.copy(alpha = 0.16f) else Panel,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (active) Accent.copy(alpha = 0.55f) else Stroke),
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
        TabButton("ظاهر", "style", tab, onTab, Modifier.weight(1f))
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
            .background(if (active) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onTab(key) }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) AccentSoft else TextMuted, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
internal fun TextEditor(
    captions: List<CaptionItem>, selectedIndex: Int,
    onSelect: (Int) -> Unit, onEdit: (Int, String) -> Unit,
) {
    val caption = captions.getOrNull(selectedIndex) ?: return
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ویرایش کپشن", color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${selectedIndex + 1} از ${captions.size}", color = Mint, fontSize = 12.sp)
        }
        OutlinedTextField(
            value = caption.text, onValueChange = { onEdit(selectedIndex, it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("${formatTime(caption.start)} تا ${formatTime(caption.end)}") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = Accent, unfocusedBorderColor = Stroke,
                focusedLabelColor = AccentSoft, unfocusedLabelColor = TextMuted, cursorColor = Accent,
            ),
            shape = RoundedCornerShape(20.dp), minLines = 2, maxLines = 5,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onSelect(selectedIndex - 1) }, enabled = selectedIndex > 0, modifier = Modifier.weight(1f)) { Text("کپشن قبلی") }
            OutlinedButton(onClick = { onSelect(selectedIndex + 1) }, enabled = selectedIndex < captions.lastIndex, modifier = Modifier.weight(1f)) { Text("کپشن بعدی") }
        }
    }
}

@Composable
internal fun StyleEditor(
    preset: String,
    fontSize: Float,
    landscape: Boolean = false,
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
        Text("ظاهر زیرنویس", color = TextPrimary, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            itemsIndexed(
                listOf(
                    "luxury" to "طلایی",
                    "bold" to "درشت",
                    "minimal" to "ساده",
                    "neon" to "نئون",
                    "glass" to "شیشه‌ای",
                )
            ) { _, item ->
                val active = preset == item.first
                Surface(
                    color = if (active) Accent.copy(alpha = 0.16f) else Panel,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (active) Accent else Stroke),
                    modifier = Modifier.clickable { onPreset(item.first) },
                ) {
                    Text(
                        item.second,
                        color = if (active) AccentSoft else TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        TextButton(onClick = { onFontSize(CaptionStyle.defaultFont(landscape)) }) { Text(if (landscape) "اندازهٔ پیشنهادی برای ویدیوی افقی" else "اندازهٔ پیشنهادی برای ویدیوی عمودی", color = AccentSoft) }
        Text("اندازهٔ متن ${if (landscape) "افقی" else "عمودی"}: ${fontSize.roundToInt()}", color = TextMuted, fontSize = 12.sp)
        Slider(value = fontSize, onValueChange = onFontSize, valueRange = CaptionStyle.MIN_FONT..CaptionStyle.maxFont(landscape))

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
        Slider(value = marginV, onValueChange = onMarginV, valueRange = CaptionStyle.MIN_MARGIN..CaptionStyle.MAX_MARGIN)
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
    captionTextColors.firstOrNull { it.key == key }?.color ?: Color(0xFFFFE2A6)

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
        color = if (selected) Accent.copy(alpha = 0.12f) else Panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) Accent else Stroke),
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
            Text(label, color = if (selected) AccentSoft else TextPrimary, fontSize = 11.sp)
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
                Text("کلمات زمان‌بندی‌شده: ${caption.words.size}", color = AccentSoft)
            }
            Text(
                "با انتخاب هر کپشن از خط زمان، همان بخش ویدیو پخش می‌شود.",
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
