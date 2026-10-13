package com.farsicaption.premium

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

class LocalCaptionEngine(private val context: Context) {

    private val correctionStore = UserCorrectionStore(context)

    private data class ModelSpec(
        val name: String,
        val url: String,
        val label: String,
        val expectedMinBytes: Long,
        val downloadLabel: String,
    )

    companion object {
        private val SMALL_MODEL = ModelSpec(
            name = "ggml-small.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            label = "Whisper Small",
            expectedMinBytes = 350_000_000L,
            downloadLabel = "~466MB",
        )

        private val TURBO_MODEL = ModelSpec(
            name = "ggml-large-v3-turbo.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin",
            label = "Whisper Large‑V3‑Turbo",
            expectedMinBytes = 1_100_000_000L,
            downloadLabel = "~1.5GB",
        )

        // Large-V3-Turbo is selected only when the device has enough physical RAM.
        private const val TURBO_MIN_RAM_BYTES = 6L * 1024L * 1024L * 1024L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .callTimeout(40, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(
        uri: Uri,
        maxWords: Int,
        language: String = "fa",
        onStatus: (String) -> Unit,
        onModelProgress: (Int) -> Unit = {},
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val preferred = preferredModelSpec()
        val initialModel = try {
            ensureModel(preferred, onStatus, onModelProgress)
        } catch (primaryError: Exception) {
            if (preferred.name == SMALL_MODEL.name) throw primaryError
            onStatus("دانلود موتور Turbo کامل نشد؛ در حال استفاده از موتور پایدار Small…")
            ensureModel(SMALL_MODEL, onStatus, onModelProgress)
        }

        onStatus("در حال بهینه‌سازی صدا برای تشخیص فارسی…")
        val video = copyUriToCache(uri, "source-${System.currentTimeMillis()}.mp4")
        val audio = File(context.cacheDir, "speech-${System.currentTimeMillis()}.wav")

        try {
            extractSpeechOptimizedAudio(video, audio)

            val captions = try {
                transcribeAudioWithModel(
                    modelFile = initialModel,
                    audio = audio,
                    maxWords = maxWords,
                    language = language,
                    onStatus = onStatus,
                )
            } catch (primaryError: Exception) {
                if (initialModel.name == SMALL_MODEL.name) throw primaryError

                onStatus("Turbo روی این دستگاه پایدار نبود؛ بازگشت خودکار به Whisper Small…")
                val fallback = ensureModel(SMALL_MODEL, onStatus, onModelProgress)
                transcribeAudioWithModel(
                    modelFile = fallback,
                    audio = audio,
                    maxWords = maxWords,
                    language = language,
                    onStatus = onStatus,
                )
            }

            if (captions.isEmpty()) {
                error("گفتار فارسی قابل استفاده‌ای پیدا نشد")
            }

            TranscriptionResult(
                fullText = captions.joinToString(" ") { it.text },
                duration = captions.maxOfOrNull { it.end } ?: 0.0,
                captions = captions,
            )
        } finally {
            video.delete()
            audio.delete()
        }
    }

    private suspend fun transcribeAudioWithModel(
        modelFile: File,
        audio: File,
        maxWords: Int,
        language: String,
        onStatus: (String) -> Unit,
    ): List<CaptionItem> {
        val label = if (modelFile.name == TURBO_MODEL.name) TURBO_MODEL.label else SMALL_MODEL.label
        onStatus("در حال تشخیص گفتار با $label…")

        val model = Whisper.loadModel(context, modelFile.absolutePath)
        return try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val result = Whisper.transcribe(
                model,
                audio.absolutePath,
                WhisperConfig(
                    language = language.takeIf { it in setOf("fa", "ar", "auto") } ?: "fa",
                    threads = threads,
                    printTimestamps = true,
                    maxSegmentLength = 80,
                ),
            )

            val captions = mutableListOf<CaptionItem>()
            var previousSegmentText = ""
            var previousSegmentEnd = 0.0
            result.segments.forEach { segment ->
                val cleaned = cleanSpeechText(segment.text, language)
                if (cleaned.isBlank()) return@forEach

                val incremental = incrementalText(previousSegmentText, cleaned)
                val rawStart = segment.startMs.toDouble() / 1000.0
                val end = max(rawStart + 0.18, segment.endMs.toDouble() / 1000.0)
                if (end <= previousSegmentEnd && rawStart < previousSegmentEnd) return@forEach

                if (incremental.isNotBlank()) {
                    val start = max(rawStart, previousSegmentEnd)
                    if (end > start) {
                        captions += splitSegment(incremental, start, end, maxWords.coerceIn(2, 7))
                    }
                }

                previousSegmentText = cleaned
                previousSegmentEnd = max(previousSegmentEnd, end)
            }

            dedupeLikelyHallucinations(captions)
        } finally {
            Whisper.releaseModel(model)
        }
    }

    private fun extractSpeechOptimizedAudio(video: File, audio: File) {
        // Gentle speech-focused preprocessing: remove rumble/high-frequency noise,
        // reduce stationary noise, then normalize loudness for more stable ASR.
        val enhanced = FFmpegKit.execute(
            "-y -i ${q(video.absolutePath)} -vn -ac 1 -ar 16000 " +
                "-af \"highpass=f=75,lowpass=f=7800\" " +
                "-c:a pcm_s16le ${q(audio.absolutePath)}"
        )
        if (ReturnCode.isSuccess(enhanced.returnCode) && audio.exists() && audio.length() > 1024L) {
            return
        }

        // Never block transcription just because a filter is unavailable on a device.
        val basic = FFmpegKit.execute(
            "-y -i ${q(video.absolutePath)} -vn -ac 1 -ar 16000 -c:a pcm_s16le ${q(audio.absolutePath)}"
        )
        if (!ReturnCode.isSuccess(basic.returnCode)) {
            error("استخراج صدا انجام نشد")
        }
    }

    internal fun incrementalText(previous: String, current: String): String {
        val prevTokens = previous.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val currentTokens = current.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (prevTokens.isEmpty() || currentTokens.size <= prevTokens.size) return current

        fun key(token: String) = token
            .replace("‌", "")
            .trim('،', '؛', ':', '.', '!', '؟', '?', '«', '»', '(', ')')

        val prefixMatches = prevTokens.indices.all { index ->
            key(prevTokens[index]) == key(currentTokens[index])
        }
        if (!prefixMatches) return current

        return currentTokens.drop(prevTokens.size).joinToString(" ")
    }

    private fun dedupeLikelyHallucinations(items: List<CaptionItem>): List<CaptionItem> {
        if (items.size < 2) return items
        val out = mutableListOf<CaptionItem>()
        for (item in items) {
            val previous = out.lastOrNull()
            val sameText = previous != null &&
                previous.text.replace("‌", "").trim() == item.text.replace("‌", "").trim()
            val tinyGap = previous != null && item.start - previous.end < 0.45
            if (sameText && tinyGap) continue
            out += item
        }
        return out
    }

    suspend fun export(
        uri: Uri,
        captions: List<CaptionItem>,
        preset: String,
        fontSize: Int,
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
        val frameSize = readVideoFrameSize(video)
        ass.writeText(
            buildAss(
                frameWidth = frameSize.first,
                frameHeight = frameSize.second,
                captions = captions,
                preset = preset,
                fontSize = fontSize,
                marginV = marginV,
                textColorKey = textColorKey,
                backgroundKey = backgroundKey,
            ),
            Charsets.UTF_8,
        )

        try {
            onStatus("در حال رندر ویدیو…")
            val cmd = "-y -i ${q(video.absolutePath)} " +
                "-vf ass=${q(ass.absolutePath)} " +
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

    fun preferredModelLabel(): String = preferredModelSpec().label

    fun preferredModelDownloadLabel(): String = preferredModelSpec().downloadLabel

    fun isModelReady(): Boolean {
        val preferred = preferredModelSpec()
        val preferredFile = modelFile(preferred)
        if (preferredFile.exists() && preferredFile.length() >= preferred.expectedMinBytes) return true

        // A valid Small model is still considered ready because it is the automatic fallback.
        val small = modelFile(SMALL_MODEL)
        return small.exists() && small.length() >= SMALL_MODEL.expectedMinBytes
    }

    private fun preferredModelSpec(): ModelSpec {
        // Fast mode is the default: Small is substantially lighter for on-device transcription.
        // Turbo remains available as a fallback target for future explicit "Accurate" mode.
        return SMALL_MODEL
    }

    private fun modelFile(spec: ModelSpec): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        return File(dir, spec.name)
    }

    private fun ensureModel(
        spec: ModelSpec,
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): File {
        val target = modelFile(spec)
        if (target.exists() && target.length() >= spec.expectedMinBytes) return target

        onStatus("دانلود یک‌باره ${spec.label} (${spec.downloadLabel})…")
        val part = File(target.parentFile, "${spec.name}.part")
        if (part.exists()) part.delete()

        val request = Request.Builder().url(spec.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("دانلود موتور فارسی ناموفق بود: ${response.code}")
            }
            val body = response.body ?: error("فایل موتور خالی بود")
            val total = body.contentLength()

            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = 0L
                    var lastProgress = -1

                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n

                        if (total > 0) {
                            val progress = ((downloaded * 100) / total)
                                .toInt()
                                .coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }

        if (part.length() < spec.expectedMinBytes) {
            part.delete()
            error("دانلود موتور ناقص بود")
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        onProgress(100)
        return target
    }

    private fun copyUriToCache(uri: Uri, name: String): File {
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: error("ویدیو قابل خواندن نیست")
        return out
    }

    private fun splitSegment(
        text: String,
        start: Double,
        end: Double,
        maxWords: Int,
    ): List<CaptionItem> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val chunks = smartChunks(tokens, maxWords)
        val span = max(0.2, end - start)

        val weights = tokens.map { token ->
            token.replace(Regex("[،؛:,.!?؟«»]"), "")
                .replace("‌", "")
                .length.coerceIn(1, 10).toDouble()
        }
        val totalWeight = weights.sum().coerceAtLeast(1.0)

        val starts = MutableList(tokens.size) { start }
        val ends = MutableList(tokens.size) { start }
        var cursor = start
        tokens.indices.forEach { index ->
            starts[index] = cursor
            cursor += span * (weights[index] / totalWeight)
            ends[index] = minOf(end, max(starts[index] + 0.04, cursor))
        }
        ends[ends.lastIndex] = end

        val result = mutableListOf<CaptionItem>()
        var globalIndex = 0
        chunks.forEach { chunk ->
            val words = chunk.mapIndexed { localIndex, token ->
                val idx = globalIndex + localIndex
                TimedWord(token, starts[idx], ends[idx])
            }
            val chunkStart = words.first().start
            val chunkEnd = words.last().end
            result += CaptionItem(
                text = chunk.joinToString(" "),
                start = chunkStart,
                end = max(chunkStart + 0.12, chunkEnd),
                words = words,
            )
            globalIndex += chunk.size
        }
        return result
    }

    private fun smartChunks(tokens: List<String>, maxWords: Int): List<List<String>> {
        if (tokens.size <= maxWords) return listOf(tokens)

        val output = mutableListOf<List<String>>()
        var index = 0
        while (index < tokens.size) {
            val hardEnd = minOf(tokens.size, index + maxWords)
            var end = hardEnd

            for (candidate in hardEnd - 1 downTo maxOf(index + 1, hardEnd - 2)) {
                val token = tokens[candidate]
                if (
                    token.endsWith("،") ||
                    token.endsWith("؛") ||
                    token.endsWith(".") ||
                    token.endsWith("!") ||
                    token.endsWith("؟") ||
                    token.endsWith("?")
                ) {
                    end = candidate + 1
                    break
                }
            }

            output += tokens.subList(index, end)
            index = end
        }
        return output
    }

    private fun cleanSpeechText(raw: String, language: String): String {
        val stripped = raw
            .replace(Regex("\\[(music|موسیقی|silence|آهنگ|applause|تشویق)[^]]*]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\((music|موسیقی|silence|آهنگ|applause|تشویق)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
            .trim()

        val text = if (language == "ar" || (language == "auto" && !Regex("[پچژگکیی]").containsMatchIn(stripped))) stripped else PersianTextNormalizer.normalize(stripped, correctionStore.all())
        val normalized = text.lowercase().replace("[", "").replace("]", "").trim()

        if (normalized in setOf("music", "موسیقی", "آهنگ", "silence", "applause", "تشویق")) {
            return ""
        }
        return text
    }

    fun correctionCount(): Int = correctionStore.size()

    fun learnCorrection(original: String, edited: String) {
        correctionStore.learn(original, edited)
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

    private fun buildAss(
        frameWidth: Int,
        frameHeight: Int,
        captions: List<CaptionItem>,
        preset: String,
        fontSize: Int,
        marginV: Int,
        textColorKey: String,
        backgroundKey: String,
    ): String {
        val primary = when (textColorKey) {
            "white" -> "&H00FFFFFF"
            "cream" -> "&H00A6E2FF"
            "yellow" -> "&H005CE4FF"
            "cyan" -> "&H00FFE675"
            "mint" -> "&H00C8F583"
            "red" -> "&H006B6BFF"
            "black" -> "&H00111111"
            else -> when (preset) {
                "luxury" -> "&H00A6E2FF"
                "neon" -> "&H00C8F583"
                else -> "&H00FFFFFF"
            }
        }

        val (borderStyle, back) = when (backgroundKey) {
            "black60" -> 3 to "&H66000000"
            "black90" -> 3 to "&H1A000000"
            "white80" -> 3 to "&H33FFFFFF"
            "gold80" -> 3 to "&H333AA8D6"
            "navy80" -> 3 to "&H332A1A10"
            else -> 1 to "&HFF000000"
        }
        val outline = when (preset) {
            "minimal" -> 2
            "bold" -> 5
            else -> 3
        }

        val scale = frameHeight / 1920.0
        val scaledFontSize = (fontSize.coerceIn(36, 82) * scale).toInt().coerceAtLeast(12)
        val scaledMargin = (marginV.coerceIn(40, 420) * scale).toInt().coerceAtLeast(8)
        val scaledSideMargin = (60 * scale).toInt().coerceAtLeast(8)
        val scaledOutline = (outline * scale).toInt().coerceAtLeast(1)
        val scaledShadow = scale.toInt().coerceAtLeast(1)

        val header = """[Script Info]
ScriptType: v4.00+
PlayResX: $frameWidth
PlayResY: $frameHeight
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Premium,sans-serif,$scaledFontSize,$primary,&H00FFFFFF,&H00101010,$back,-1,0,0,0,100,100,0,0,$borderStyle,$scaledOutline,$scaledShadow,2,$scaledSideMargin,$scaledSideMargin,$scaledMargin,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

        val events = mutableListOf<String>()

        captions.forEach { caption ->
            events += assDialogue(caption.start, caption.end, caption.text)
        }

        return header + events.joinToString("\n") + "\n"
    }

    private fun assDialogue(start: Double, end: Double, rawText: String): String {
        val clean = "\u200F" + rawText
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\n", "\\N")

        return "Dialogue: 0,${assTime(start)},${assTime(end)},Premium,,0,0,0,,$clean"
    }

    private fun assTime(seconds: Double): String {
        val cs = (seconds.coerceAtLeast(0.0) * 100.0).toInt()
        val h = cs / 360000
        val m = (cs % 360000) / 6000
        val s = (cs % 6000) / 100
        val c = cs % 100
        return "%d:%02d:%02d.%02d".format(h, m, s, c)
    }

    private fun q(path: String): String = "\'" + path + "\'"
}
