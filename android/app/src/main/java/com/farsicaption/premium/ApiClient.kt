package com.farsicaption.premium

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class TimedWord(
    val text: String,
    val start: Double,
    val end: Double,
)

data class CaptionItem(
    val text: String,
    val start: Double,
    val end: Double,
    val words: List<TimedWord> = emptyList(),
)

data class TranscriptionResult(
    val fullText: String,
    val duration: Double,
    val captions: List<CaptionItem>,
)

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.MINUTES)
        .writeTimeout(20, TimeUnit.MINUTES)
        .callTimeout(25, TimeUnit.MINUTES)
        .build()

    fun transcribe(
        serverUrl: String,
        appToken: String,
        uri: Uri,
        maxWords: Int = 5,
    ): TranscriptionResult {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("max_words", maxWords.coerceIn(2, 8).toString())
            .addVideoPart(uri)
            .build()

        val request = requestBuilder(serverUrl, appToken, "/v2/transcribe")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throwApiError(response.code, response.body?.string())
            val raw = response.body?.string() ?: error("پاسخ تشخیص خالی بود")
            val root = JSONObject(raw)
            val captionsJson = root.optJSONArray("captions") ?: JSONArray()
            val captions = buildList {
                for (i in 0 until captionsJson.length()) {
                    val c = captionsJson.getJSONObject(i)
                    val wordsJson = c.optJSONArray("words") ?: JSONArray()
                    val words = buildList {
                        for (j in 0 until wordsJson.length()) {
                            val w = wordsJson.getJSONObject(j)
                            add(TimedWord(w.optString("text"), w.optDouble("start"), w.optDouble("end")))
                        }
                    }
                    add(
                        CaptionItem(
                            text = c.optString("text"),
                            start = c.optDouble("start"),
                            end = c.optDouble("end"),
                            words = words,
                        )
                    )
                }
            }
            return TranscriptionResult(
                fullText = root.optString("full_text"),
                duration = root.optDouble("duration"),
                captions = captions,
            )
        }
    }

    fun export(
        serverUrl: String,
        appToken: String,
        uri: Uri,
        captions: List<CaptionItem>,
        preset: String,
        fontSize: Int,
        marginV: Int,
    ): File {
        val json = JSONArray()
        captions.forEach { caption ->
            val words = JSONArray()
            caption.words.forEach { word ->
                words.put(
                    JSONObject()
                        .put("text", word.text)
                        .put("start", word.start)
                        .put("end", word.end)
                )
            }
            json.put(
                JSONObject()
                    .put("text", caption.text)
                    .put("start", caption.start)
                    .put("end", caption.end)
                    .put("words", words)
            )
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("captions_json", json.toString())
            .addFormDataPart("preset", preset)
            .addFormDataPart("font_size", fontSize.coerceIn(34, 82).toString())
            .addFormDataPart("margin_v", marginV.coerceIn(30, 420).toString())
            .addFormDataPart("progressive", "true")
            .addVideoPart(uri)
            .build()

        val request = requestBuilder(serverUrl, appToken, "/v2/export")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throwApiError(response.code, response.body?.string())
            val out = File(context.cacheDir, "FarsiCaption-${System.currentTimeMillis()}.mp4")
            response.body?.byteStream()?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: error("خروجی ویدیو خالی بود")
            return out
        }
    }

    fun queryName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun requestBuilder(serverUrl: String, appToken: String, path: String): Request.Builder {
        return Request.Builder()
            .url(serverUrl.trimEnd('/') + path)
            .apply {
                if (appToken.isNotBlank()) header("X-App-Token", appToken.trim())
            }
    }

    private fun MultipartBody.Builder.addVideoPart(uri: Uri): MultipartBody.Builder {
        val mime = context.contentResolver.getType(uri) ?: "video/mp4"
        val fileName = queryName(uri) ?: "video.mp4"
        return addFormDataPart("video", fileName, ContentUriBody(context, uri, mime))
    }

    private fun throwApiError(code: Int, raw: String?): Nothing {
        val detail = runCatching {
            JSONObject(raw ?: "").optString("detail")
        }.getOrNull().orEmpty().ifBlank { raw.orEmpty().take(1200) }
        throw IllegalStateException("خطای سرور ($code): $detail")
    }

    private class ContentUriBody(
        private val context: Context,
        private val uri: Uri,
        private val mime: String,
    ) : RequestBody() {
        override fun contentType() = mime.toMediaTypeOrNull()

        override fun contentLength(): Long {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getLong(0)
            }
            return -1
        }

        override fun writeTo(sink: BufferedSink) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                }
            } ?: error("ویدیو قابل خواندن نیست")
        }
    }
}
