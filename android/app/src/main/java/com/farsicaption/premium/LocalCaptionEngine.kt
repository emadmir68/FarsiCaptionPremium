package com.farsicaption.premium

import android.content.Context
import android.net.Uri
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

    companion object {
        private const val MODEL_NAME = "ggml-small.bin"
        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .callTimeout(40, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(
        uri: Uri,
        maxWords: Int,
        onStatus: (String) -> Unit,
        onModelProgress: (Int) -> Unit = {},
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val modelFile = ensureModel(onStatus, onModelProgress)
        onStatus("در حال آماده‌سازی صدای ویدیو…")

        val video = copyUriToCache(uri, "source-${System.currentTimeMillis()}.mp4")
        val audio = File(context.cacheDir, "speech-${System.currentTimeMillis()}.wav")

        try {
            val extract = FFmpegKit.execute(
                "-y -i ${q(video.absolutePath)} -vn -ac 1 -ar 16000 -c:a pcm_s16le ${q(audio.absolutePath)}"
            )
            if (!ReturnCode.isSuccess(extract.returnCode)) {
                error("استخراج صدا انجام نشد")
            }

            onStatus("در حال تشخیص گفتار فارسی روی گوشی…")
            val model = Whisper.loadModel(context, modelFile.absolutePath)
            try {
                val result = Whisper.transcribe(
                    model,
                    audio.absolutePath,
                    WhisperConfig(language = "fa"),
                )

                val captions = mutableListOf<CaptionItem>()
                result.segments.forEach { segment ->
                    val cleaned = cleanSpeechText(segment.text)
                    if (cleaned.isBlank()) return@forEach

                    val start = segment.startMs.toDouble() / 1000.0
                    val end = max(start + 0.18, segment.endMs.toDouble() / 1000.0)
                    captions += splitSegment(cleaned, start, end, maxWords.coerceIn(2, 7))
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
                Whisper.releaseModel(model)
            }
        } finally {
            video.delete()
            audio.delete()
        }
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
        ass.writeText(
            buildAss(
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

    fun isModelReady(): Boolean = modelFile().exists() && modelFile().length() > 100_000_000L

    private fun modelFile(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        return File(dir, MODEL_NAME)
    }

    private fun ensureModel(
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): File {
        val target = modelFile()
        if (target.exists() && target.length() > 100_000_000L) return target

        onStatus("دانلود یک‌باره موتور فارسی باکیفیت (~466MB)…")
        val part = File(target.parentFile, "$MODEL_NAME.part")
        if (part.exists()) part.delete()

        val request = Request.Builder().url(MODEL_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("دانلود موتور فارسی ناموفق بود: ${response.code}")
            val body = response.body ?: error("فایل موتور خالی بود")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    var lastProgress = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }

        if (part.length() < 100_000_000L) {
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
        val chunks = tokens.chunked(maxWords)
        val span = max(0.2, end - start)
        val wordStep = span / tokens.size
        var globalIndex = 0

        return chunks.map { chunk ->
            val chunkStart = start + globalIndex * wordStep
            val words = chunk.mapIndexed { index, token ->
                val a = start + (globalIndex + index) * wordStep
                val b = start + (globalIndex + index + 1) * wordStep
                TimedWord(token, a, max(a + 0.04, b))
            }
            globalIndex += chunk.size
            val chunkEnd = words.lastOrNull()?.end ?: chunkStart + 0.2
            CaptionItem(
                text = chunk.joinToString(" "),
                start = chunkStart,
                end = chunkEnd,
                words = words,
            )
        }
    }

    private fun cleanSpeechText(raw: String): String {
        var text = raw
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .replace(Regex("\\[(music|موسیقی|silence|آهنگ)[^]]*]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\((music|موسیقی|silence|آهنگ)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val spacingFixes = listOf(
            "هم کاران" to "همکاران",
            "هم کار" to "همکار",
            "هم کاری" to "همکاری",
            "می شود" to "می‌شود",
            "نمی شود" to "نمی‌شود",
            "می کنم" to "می‌کنم",
            "می کنیم" to "می‌کنیم",
            "می کنید" to "می‌کنید",
            "می کنند" to "می‌کنند",
            "می خواهم" to "می‌خواهم",
            "می خواهید" to "می‌خواهید",
            "می تواند" to "می‌تواند",
            "می توانند" to "می‌توانند",
            "نمی توان" to "نمی‌توان",
        )
        spacingFixes.forEach { (wrong, right) ->
            text = text.replace(wrong, right)
        }

        val normalized = text.lowercase()
        if (
            normalized == "music" ||
            normalized == "موسیقی" ||
            normalized == "آهنگ" ||
            normalized == "silence"
        ) return ""

        return text
    }

    private fun buildAss(
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

        val header = """[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Premium,sans-serif,${fontSize.coerceIn(36,82)},$primary,&H00FFFFFF,&H00101010,$back,-1,0,0,0,100,100,0,0,$borderStyle,$outline,1,2,60,60,${marginV.coerceIn(40,420)},1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""

        val events = captions.joinToString("\n") { c ->
            val clean = "\u200F" + c.text
                .replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\n", "\\N")
            "Dialogue: 0,${assTime(c.start)},${assTime(c.end)},Premium,,0,0,0,,$clean"
        }
        return header + events + "\n"
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
