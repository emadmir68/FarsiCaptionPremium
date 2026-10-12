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
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()

    fun process(
        serverUrl: String,
        appToken: String,
        uri: Uri,
        maxWords: Int = 5,
        fontSize: Int = 52,
    ): File {
        val mime = context.contentResolver.getType(uri) ?: "video/mp4"
        val fileName = queryName(uri) ?: "video.mp4"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("max_words", maxWords.toString())
            .addFormDataPart("font_size", fontSize.toString())
            .addFormDataPart("video", fileName, ContentUriBody(context, uri, mime))
            .build()

        val requestBuilder = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/v1/caption")
            .post(body)
        if (appToken.isNotBlank()) requestBuilder.header("X-App-Token", appToken.trim())

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val error = response.body?.string()?.take(1200) ?: "HTTP ${response.code}"
                throw IllegalStateException("خطای پردازش: $error")
            }
            val out = File(context.cacheDir, "captioned-${System.currentTimeMillis()}.mp4")
            response.body?.byteStream()?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: error("پاسخ ویدیو خالی بود")
            return out
        }
    }

    fun queryName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
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
            } ?: error("نمی‌توان ویدیو را باز کرد")
        }
    }
}
