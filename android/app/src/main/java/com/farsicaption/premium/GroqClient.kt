package com.farsicaption.premium

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class GroqClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).writeTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val endpoint: String = "https://api.groq.com/openai/v1/audio/transcriptions",
) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var call: okhttp3.Call? = null
    fun cancel() { cancelled.set(true); call?.cancel() }
    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("لغو شد") }

    suspend fun transcribe(file: File, key: String, language: String, mode: String,
        progress: (Int) -> Unit = {}, status: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        require(file.length() in 45..GroqProtocol.MAX_FILE_BYTES) { "حجم فایل صوتی قابل ارسال نیست" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", GroqMode.model(mode))
            .addFormDataPart("language", SpeechLanguagePolicy.whisperLanguage(language))
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("file", "speech.wav", object : RequestBody() {
                override fun contentType() = "audio/wav".toMediaType()
                override fun contentLength() = file.length()
                override fun writeTo(sink: BufferedSink) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024); var sent = 0L; var last = -1
                        while (true) {
                            checkCancelled(); val n = input.read(buffer); if (n < 0) break
                            sink.write(buffer, 0, n); sent += n
                            val percent = (sent * 100 / file.length()).toInt()
                            if (last != percent) { last = percent; progress(percent) }
                        }
                    }
                    status("صدا ارسال شد؛ منتظر تشخیص گفتار Groq…")
                }
            }).build()
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(body).build()
        var attempt = 0
        while (true) {
            checkCancelled()
            val requestCall = client.newCall(request); call = requestCall
            try {
                checkCancelled()
                var retrySeconds: Long? = null
                requestCall.execute().use { response ->
                    checkCancelled()
                    if (response.isSuccessful) {
                        val responseBody = response.body ?: error("پاسخ Groq خالی است")
                        val bytes = responseBody.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                checkCancelled(); val n = input.read(buffer); if (n < 0) break
                                check(output.size() + n <= 8 * 1024 * 1024) { "پاسخ آنلاین بیش از حد بزرگ است" }
                                output.write(buffer, 0, n)
                            }
                            output.toByteArray()
                        }
                        return@withContext String(bytes, Charsets.UTF_8)
                    }
                    // Only retry an explicitly rejected request, never an uncertain network failure.
                    val after = response.header("Retry-After")?.toLongOrNull()
                    if (response.code == 429 && attempt == 0 && after != null && after in 1..30) retrySeconds = after
                    else throw IllegalStateException(GroqProtocol.httpError(response.code))
                }
                val wait = retrySeconds ?: error("پاسخ آنلاین نامعتبر است")
                status("ظرفیت موقتاً پر است؛ تلاش دوباره پس از $wait ثانیه…")
                delay(wait * 1000); attempt++
            } catch (e: SocketTimeoutException) {
                checkCancelled(); throw IllegalStateException("زمان انتظار Groq تمام شد؛ اتصال را بررسی کنید و دوباره تلاش کنید")
            } catch (e: IOException) {
                checkCancelled(); throw IllegalStateException("ارتباط با Groq قطع شد؛ دوباره تلاش کنید یا حالت آفلاین را انتخاب کنید")
            } finally { if (call === requestCall) call = null }
        }
        @Suppress("UNREACHABLE_CODE") error("پاسخ دریافت نشد")
    }
}
