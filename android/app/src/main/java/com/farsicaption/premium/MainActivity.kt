package com.farsicaption.premium

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PremiumCaptionApp() }
    }
}

@Composable
private fun PremiumCaptionApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("caption_settings", Context.MODE_PRIVATE) }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var outputFile by remember { mutableStateOf<File?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("ویدیو را انتخاب کن؛ زیرنویس فقط هنگام صحبت نمایش داده می‌شود.") }
    var serverUrl by remember { mutableStateOf(prefs.getString("server", "http://10.0.2.2:8000") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var maxWords by remember { mutableFloatStateOf(5f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sourceUri = uri
            outputFile = null
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            status = "ویدیو انتخاب شد. آماده‌ی تشخیص فارسی Premium."
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme {
            Scaffold { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("زیرنویس فارسی Premium", style = MaterialTheme.typography.headlineMedium)
                    Text("Auto Caption فارسی با زمان‌بندی کلمه‌ای؛ متن فقط هم‌زمان با گفتار ظاهر می‌شود.")

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("تنظیمات موتور", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("آدرس Backend") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                label = { Text("App Token (اختیاری در تست محلی)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("تعداد کلمه در هر کپشن: ${maxWords.toInt()}")
                            Slider(value = maxWords, onValueChange = { maxWords = it }, valueRange = 2f..7f, steps = 4)
                            FilledTonalButton(onClick = {
                                prefs.edit().putString("server", serverUrl).putString("token", token).apply()
                                Toast.makeText(context, "تنظیمات ذخیره شد", Toast.LENGTH_SHORT).show()
                            }) { Text("ذخیره تنظیمات") }
                        }
                    }

                    Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("انتخاب ویدیو")
                    }

                    sourceUri?.let { uri ->
                        Text("فایل: ${remember(uri) { ApiClient(context).queryName(uri) ?: "ویدیو" }}")
                    }

                    Button(
                        enabled = sourceUri != null && !isProcessing && serverUrl.isNotBlank(),
                        onClick = {
                            val uri = sourceUri ?: return@Button
                            prefs.edit().putString("server", serverUrl).putString("token", token).apply()
                            isProcessing = true
                            status = "در حال تشخیص گفتار فارسی، حذف بخش‌های بدون صحبت و ساخت زیرنویس…"
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        ApiClient(context).process(serverUrl, token, uri, maxWords.toInt(), 52)
                                    }
                                }.onSuccess { file ->
                                    outputFile = file
                                    status = "آماده شد. پیش‌نمایش را ببین و سپس در گالری ذخیره کن."
                                }.onFailure { e ->
                                    status = e.message ?: "خطای نامشخص"
                                }
                                isProcessing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("ساخت زیرنویس هوشمند") }

                    if (isProcessing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status)

                    outputFile?.let { file ->
                        VideoPreview(file)
                        Button(
                            onClick = {
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) { saveToGallery(context, file) }
                                    Toast.makeText(context, if (saved) "ویدیو در گالری ذخیره شد" else "ذخیره انجام نشد", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("ذخیره ویدیو در گالری") }
                    }

                    HorizontalDivider()
                    Text("Premium v1.0: فارسی • تایمینگ کلمه‌ای • نمایش تدریجی • حذف بخش‌های غیرگفتاری • خروجی MP4")
                }
            }
        }
    }
}

@Composable
private fun VideoPreview(file: File) {
    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().height(420.dp)
    )
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
            context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return false
            source.copyTo(File(dir, "FarsiCaption-${System.currentTimeMillis()}.mp4"), overwrite = true)
            true
        }
    } catch (_: Exception) { false }
}
