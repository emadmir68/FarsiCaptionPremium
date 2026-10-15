package com.farsicaption.premium
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalCaptionEngine(private val context: Context) {
    private data class ModelSpec(val name: String, val url: String, val label: String,
        val size: Long, val sha256: String, val downloadLabel: String, val mels: Int = 0, val layers: Int = 0)
    companion object {
        private val SMALL_MODEL = ModelSpec("ggml-small.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            "Whisper Small", 487601967L, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", "~465MB", 80, 12)
        private val TURBO_MODEL = ModelSpec("ggml-large-v3-turbo.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin",
            "Whisper Large-V3-Turbo", 1624555275L, "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69", "~1.51GB", 128, 4)
        private val COMPACT_MODEL = ModelSpec("ggml-large-v3-turbo-q5_0.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            "Whisper Turbo Q5", 574041195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", "~548MB", 128, 4)
        private val PERSIAN_MODEL = ModelSpec("ggml-persian-turbo-givia-q5_0.bin",
            "https://github.com/emadmir68/FarsiCaptionPremium/releases/download/v3.4.2-persian/ggml-persian-turbo-givia-q5_0.bin",
            "Whisper Turbo فارسی (Givia Q5)", 574041195L, "d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8", "~548MB", 128, 4)
        private val VAD_MODEL = ModelSpec("ggml-silero-v5.1.2.bin",
            "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
            "Silero VAD", 885098L, "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf", "~865KB")
    }
    private val cancelled = AtomicBoolean(false)
    private val modelLock = Any()
    private var nativeHandle = 0L
    @Volatile private var downloadCall: okhttp3.Call? = null
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES).callTimeout(40, TimeUnit.MINUTES).build()
    fun cancel() {
        cancelled.set(true)
        downloadCall?.cancel()
        synchronized(modelLock) { if (nativeHandle != 0L) NativeWhisper.cancel(nativeHandle) }
    }
    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("لغو شد") }
    suspend fun transcribe(uri: Uri, maxWords: Int, language: String = "fa", mode: String = "quality", onStatus: (String) -> Unit,
        onModelProgress: (Int) -> Unit = {}): TranscriptionResult = withContext(Dispatchers.IO) {
        checkCancelled()
        val preferred = preferredModelSpec(mode, language)
        var selected = preferred
        val initial = try { ensureModel(preferred, onStatus, onModelProgress); preferred }
        catch (e: Exception) {
            checkCancelled()
            if (preferred != TURBO_MODEL && preferred != PERSIAN_MODEL) throw e
            onStatus("مدل انتخابی آماده نشد؛ دریافت Turbo عمومی کم‌حجم…")
            ensureModel(COMPACT_MODEL, onStatus, onModelProgress); COMPACT_MODEL
        }
        selected = initial
        val vad = ensureModel(VAD_MODEL, onStatus, onModelProgress)
        onStatus("آماده‌سازی صدای اصلی با نرخ ۱۶ کیلوهرتز…")
        val video = File.createTempFile("source-", ".mp4", context.cacheDir)
        val audio = File.createTempFile("speech-", ".wav", context.cacheDir)
        try {
            copyUri(uri, video); extractAudio(video, audio); checkCancelled()
            val raw = try { decode(initial, audio, vad, language, onStatus) }
            catch (e: Exception) {
                checkCancelled()

                if (initial == SMALL_MODEL) throw e
                val fallback = if (initial == TURBO_MODEL || initial == PERSIAN_MODEL) COMPACT_MODEL else SMALL_MODEL
                onStatus("اجرای مدل اصلی ناموفق بود؛ تلاش با ${fallback.label}…")
                ensureModel(fallback, onStatus, onModelProgress)
                selected = fallback
                try { decode(fallback, audio, vad, language, onStatus) }
                catch (fallbackError: Exception) {
                    checkCancelled()
                    if (fallback == SMALL_MODEL) throw fallbackError
                    onStatus("حافظه کافی برای Turbo نیست؛ تلاش با Whisper Small…")
                    ensureModel(SMALL_MODEL, onStatus, onModelProgress)
                    selected = SMALL_MODEL
                    decode(SMALL_MODEL, audio, vad, language, onStatus)
                }

            }
            checkCancelled()
            val root = JSONObject(raw)
            val duration = root.getDouble("duration")
            val array = root.getJSONArray("segments")
            val segments = (0 until array.length()).map { i ->
                val s = array.getJSONObject(i)
                val words = s.getJSONArray("words")
                AsrSegment(s.getString("text"), s.getLong("startMs") / 1000.0, s.getLong("endMs") / 1000.0,
                    (0 until words.length()).map { j ->
                        val w = words.getJSONObject(j)
                        TimedWord(w.getString("text"), w.getLong("startMs") / 1000.0, w.getLong("endMs") / 1000.0)
                    })
            }
            val captions = CaptionPipeline.build(segments, duration, maxWords, language)
            TranscriptionResult(captions.joinToString(" ") { it.text }, duration, captions, selected.label)
        } finally { video.delete(); audio.delete() }
    }
    private fun decode(spec: ModelSpec, audio: File, vad: File, language: String, onStatus: (String) -> Unit): String {
        checkCancelled()
        onStatus("تشخیص گفتار با ${spec.label} • زبان ${if (language == "ar") "عربی" else "فارسی"}…")
        val handle = NativeWhisper.load(modelFile(spec).absolutePath, spec.mels, spec.layers)
        synchronized(modelLock) { nativeHandle = handle }
        try {
            checkCancelled()
            return NativeWhisper.transcribe(handle, audio.absolutePath, vad.absolutePath,
                SpeechLanguagePolicy.whisperLanguage(language), Runtime.getRuntime().availableProcessors().coerceIn(1, 8)).toString(Charsets.UTF_8)
        } finally { synchronized(modelLock) { NativeWhisper.release(handle); nativeHandle = 0L } }
    }
    private fun extractAudio(video: File, audio: File) {
        // Preserve stream timestamps, including delayed audio and timestamp gaps. No denoising or lexical correction.
        val result = FFmpegKit.execute("-y -i ${q(video.absolutePath)} -map 0:a:0 -vn -ac 1 -ar 16000 -af aresample=async=1:first_pts=0 -c:a pcm_s16le ${q(audio.absolutePath)}")
        checkCancelled()
        if (!ReturnCode.isSuccess(result.returnCode) || audio.length() <= 44) error("صدای ویدیو قابل استخراج نیست")
    }
    private fun savedModelSpec(): ModelSpec {
        val preferences = context.getSharedPreferences("editor_settings", Context.MODE_PRIVATE)
        return preferredModelSpec(preferences.getString("modelMode", "quality") ?: "quality",
            preferences.getString("speechLanguage", "fa") ?: "fa")
    }
    fun preferredModelLabel(): String = savedModelSpec().label
    fun preferredModelDownloadLabel(): String = savedModelSpec().downloadLabel
    fun isModelReady(): Boolean {
        val preferred = savedModelSpec()
        return modelFile(preferred).length() == preferred.size && modelFile(VAD_MODEL).length() == VAD_MODEL.size
    }
    private fun preferredModelSpec(mode: String = "quality", language: String = "fa"): ModelSpec {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return when (ModelPolicy.select(mode, info.totalMem, info.availMem, info.lowMemory, language)) {
            ModelTier.TURBO -> TURBO_MODEL
            ModelTier.TURBO_COMPACT -> COMPACT_MODEL
            ModelTier.SMALL -> SMALL_MODEL
            ModelTier.PERSIAN_TURBO -> PERSIAN_MODEL
        }
    }
    private fun modelFile(spec: ModelSpec) = File(File(context.filesDir, "models").apply { mkdirs() }, spec.name)
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun ensureModel(spec: ModelSpec, status: (String) -> Unit, progress: (Int) -> Unit): File {
        checkCancelled()
        val target = modelFile(spec)
        val verification = context.getSharedPreferences("model_verification_v1", Context.MODE_PRIVATE)
        val stamp = "${spec.sha256}:${target.length()}:${target.lastModified()}"
        if (target.length() == spec.size) {
            if (verification.getString(spec.name, null) == stamp) { progress(100); return target }
            status("بررسی سلامت ${spec.label}…")
            if (digest(target) == spec.sha256) { verification.edit().putString(spec.name, stamp).apply(); progress(100); return target }
        }
        status("دانلود یک‌باره ${spec.label} (${spec.downloadLabel})…")
        val part = File(target.parentFile, "${spec.name}.part")
        try {
            val call = client.newCall(Request.Builder().url(spec.url).build()); downloadCall = call
            call.execute().use { response ->
                check(response.isSuccessful) { "دانلود مدل ناموفق بود: ${response.code}" }
                val body = response.body ?: error("فایل مدل خالی بود")
                body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024); var count = 0L; var last = -1
                    while (true) {
                        checkCancelled(); val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); count += n
                        check(count <= spec.size) { "اندازه مدل نامعتبر است" }
                        val percent = (count * 100 / spec.size).toInt()
                        if (percent != last) { last = percent; progress(percent) }
                    }
                } }
            }
            check(part.length() == spec.size && digest(part) == spec.sha256) { "مدل ناقص یا ناسالم است؛ دوباره تلاش کنید" }
            checkCancelled()
            check(part.renameTo(target)) { "ذخیره مدل انجام نشد" }
            verification.edit().putString(spec.name, "${spec.sha256}:${target.length()}:${target.lastModified()}").apply()
            return target
        } finally { downloadCall = null; part.delete() }
    }
    private fun copyUri(uri: Uri, out: File) {
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
        } } ?: error("ویدیو قابل خواندن نیست")
    }
    private fun copyUriToCache(uri: Uri, name: String): File = File(context.cacheDir, name).also { copyUri(uri, it) }

    suspend fun export(
        uri: Uri,
        captions: List<CaptionItem>,
        preset: String,
        fontSize: Int,
        landscapeFontSize: Int = CaptionStyle.DEFAULT_LANDSCAPE_FONT.toInt(),
        marginV: Int,
        textColorKey: String,
        backgroundKey: String,
        onStatus: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(captions.isNotEmpty()) { "زیرنویسی برای خروجی وجود ندارد" }

        onStatus("در حال ساخت فایل زیرنویس…")
        val video = copyUriToCache(uri, "export-source-${System.currentTimeMillis()}.mp4")
        val ass = File(context.cacheDir, "captions-${System.currentTimeMillis()}.ass")
        val output = File(context.cacheDir, "FarsiCaption-${System.currentTimeMillis()}.mp4")
        val fonts = File(context.filesDir, "subtitle-fonts").apply { mkdirs() }
        val font = File(fonts, "Vazirmatn-Regular.ttf")
        if (!font.exists()) context.assets.open("fonts/Vazirmatn-Regular.ttf").use { input -> font.outputStream().use { input.copyTo(it) } }
        val frameSize = readVideoFrameSize(video)
        ass.writeText(
            CaptionAss.build(
                frameWidth = frameSize.first,
                frameHeight = frameSize.second,
                captions = captions,
                preset = preset,
                fontSize = CaptionStyle.selectedFont(fontSize.toFloat(), landscapeFontSize.toFloat(), frameSize.first.toFloat(), frameSize.second.toFloat()).toInt(),
                marginV = marginV,
                textColorKey = textColorKey,
                backgroundKey = backgroundKey,
            ),
            Charsets.UTF_8,
        )

        try {
            onStatus("در حال رندر ویدیو…")
            val cmd = "-y -i ${q(video.absolutePath)} " +
                "-vf ass=${q(ass.absolutePath)}:fontsdir=${q(fonts.absolutePath)} " +
                "-c:v mpeg4 -q:v 2 -c:a aac -b:a 192k -movflags +faststart ${q(output.absolutePath)}"
            val session = FFmpegKit.execute(cmd)
            if (!ReturnCode.isSuccess(session.returnCode) || !output.exists()) {
                error("رندر ویدیو انجام نشد")
            }
            output
        } finally {
            video.delete()
            ass.delete()
        }
    }

    private fun readVideoFrameSize(video: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: error("عرض ویدئو مشخص نیست")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: error("ارتفاع ویدئو مشخص نیست")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            require(width > 0 && height > 0) { "ابعاد ویدئو معتبر نیست" }
            if (rotation % 180 != 0) height to width else width to height
        } finally {
            retriever.release()
        }
    }

    private fun q(path: String): String = "\'" + path + "\'"
}
