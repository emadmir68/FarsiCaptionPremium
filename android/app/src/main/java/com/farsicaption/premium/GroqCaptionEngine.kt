package com.farsicaption.premium

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class GroqCaptionEngine(private val context: Context) {
    private val client = GroqClient()
    private val cancelled = AtomicBoolean(false)
    private val ffmpegId = AtomicLong(-1)
    fun cancel() { cancelled.set(true); client.cancel(); ffmpegId.get().takeIf { it >= 0 }?.let { FFmpegKit.cancel(it) } }
    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("لغو شد") }
    private fun q(path: String) = "'" + path.replace("'", "'\\''") + "'"
    private fun number(value: Double) = String.format(Locale.US, "%.6f", value)

    suspend fun transcribe(uri: Uri, maxWords: Int, language: String, mode: String,
        onStatus: (String) -> Unit, onProgress: (Int) -> Unit): TranscriptionResult = withContext(Dispatchers.IO) {
        checkCancelled()
        val key = GroqKeyStore(context).read()
        val directory = File(context.cacheDir, "groq-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val video = File(directory, "source.mp4")
        val audio = File(directory, "speech.wav")
        try {
            onStatus("آماده‌سازی صدای ویدیو برای حالت آنلاین…")
            context.contentResolver.openInputStream(uri)?.use { input -> video.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) { checkCancelled(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            } } ?: error("ویدیو قابل خواندن نیست")
            // Keep the same timeline filter as the offline path, including delayed audio.
            runFfmpeg("-y -i ${q(video.absolutePath)} -map 0:a:0 -vn -ac 1 -ar 16000 -af aresample=async=1:first_pts=0 -c:a pcm_s16le ${q(audio.absolutePath)}")
            check(audio.length() > 44) { "صدای ویدیو قابل استخراج نیست" }
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(audio.absolutePath)
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
            } finally { retriever.release() }
            val chunks = GroqProtocol.chunks(duration)
            val segments = mutableListOf<AsrSegment>()
            chunks.forEachIndexed { index, chunk ->
                checkCancelled()
                val file = if (chunks.size == 1) audio else File(directory, "chunk.wav").also {
                    runFfmpeg("-y -ss ${number(chunk.uploadStart)} -i ${q(audio.absolutePath)} -t ${number(chunk.uploadEnd - chunk.uploadStart)} -c:a pcm_s16le ${q(it.absolutePath)}")
                }
                onStatus("ارسال صدای بخش ${index + 1} از ${chunks.size} به Groq…")
                val raw = client.transcribe(file, key, language, mode, progress = { percent ->
                    onProgress(((index + percent / 100.0 * .7) / chunks.size * 100).toInt().coerceIn(0, 99))
                }, status = onStatus)
                checkCancelled()
                val parsed = try { GroqProtocol.parse(raw, chunk.uploadEnd - chunk.uploadStart, language) }
                catch (e: org.json.JSONException) { throw IllegalStateException("پاسخ Groq قابل خواندن نیست؛ دوباره تلاش کنید") }
                segments += GroqProtocol.owned(parsed, chunk)
                onProgress(((index + 1) * 100 / chunks.size).coerceAtMost(99))
            }
            checkCancelled()
            onStatus("آماده‌سازی متن و زمان‌بندی زیرنویس…")
            val captions = CaptionPipeline.build(segments, duration, maxWords, language)
            onProgress(100)
            TranscriptionResult(captions.joinToString(" ") { it.text }, duration, captions, GroqMode.label(mode))
        } finally { directory.deleteRecursively() }
    }

    private suspend fun runFfmpeg(command: String) {
        checkCancelled()
        val finished = kotlinx.coroutines.CompletableDeferred<com.arthenica.ffmpegkit.FFmpegSession>()
        val session = FFmpegKit.executeAsync(command) { finished.complete(it) }
        ffmpegId.set(session.sessionId)
        if (cancelled.get()) FFmpegKit.cancel(session.sessionId)
        val result = try { finished.await() }
        finally {
            if (!finished.isCompleted) {
                FFmpegKit.cancel(session.sessionId)
                withContext(kotlinx.coroutines.NonCancellable) { finished.await() }
            }
            ffmpegId.compareAndSet(session.sessionId, -1)
        }
        if (ReturnCode.isCancel(result.returnCode)) throw CancellationException("لغو شد")
        check(ReturnCode.isSuccess(result.returnCode)) { "آماده‌سازی صدای ویدیو ناموفق بود" }
        checkCancelled()
    }
}
