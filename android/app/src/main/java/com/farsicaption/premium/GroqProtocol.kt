package com.farsicaption.premium

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

internal object GroqMode {
    const val TURBO = "groq_turbo"
    const val QUALITY = "groq_quality"
    fun isOnline(mode: String) = mode == TURBO || mode == QUALITY
    fun model(mode: String): String = when (mode) {
        TURBO -> "whisper-large-v3-turbo"
        QUALITY -> "whisper-large-v3"
        else -> throw IllegalArgumentException("مدل آنلاین نامعتبر است")
    }
    fun label(mode: String) = if (mode == QUALITY) "Groq · Large V3" else "Groq · Turbo"
}

internal data class AudioChunk(val start: Double, val end: Double, val uploadStart: Double, val uploadEnd: Double)

internal object GroqProtocol {
    const val MAX_FILE_BYTES = 25_000_000L
    const val CHUNK_SECONDS = 240.0
    fun chunks(duration: Double): List<AudioChunk> {
        require(duration.isFinite() && duration > 0) { "مدت صوت نامعتبر است" }
        val out = mutableListOf<AudioChunk>()
        var start = 0.0
        while (start < duration) {
            val end = min(start + CHUNK_SECONDS, duration)
            out += AudioChunk(start, end, (start - 1.0).coerceAtLeast(0.0), min(end + 1.0, duration))
            start = end
        }
        return out
    }

    private fun words(array: JSONArray?): List<TimedWord> = if (array == null) emptyList() else
        (0 until array.length()).map { i ->
            val w = array.getJSONObject(i)
            TimedWord(w.optString("word", w.optString("text")), w.getDouble("start"), w.getDouble("end"))
        }

    private fun valid(words: List<TimedWord>, duration: Double): Boolean = words.isNotEmpty() &&
        words.all { it.text.isNotBlank() && it.start.isFinite() && it.end.isFinite() && it.start >= 0 && it.end > it.start && it.end <= duration + .1 } &&
        words.zipWithNext().all { (a, b) -> a.end <= b.start }

    /** Use actual provider times only. Never spread text over an invented interval. */
    fun parse(raw: String, duration: Double, language: String): List<AsrSegment> {
        val root = JSONObject(raw)
        val text = root.optString("text").trim()
        val aligned = words(root.optJSONArray("words"))
        if (valid(aligned, duration) && SpeechLanguagePolicy.clean(aligned.joinToString(" ") { it.text }, language) ==
            SpeechLanguagePolicy.clean(text, language)) {
            return listOf(AsrSegment(text, aligned.first().start, aligned.last().end, aligned))
        }
        val segments = root.optJSONArray("segments") ?: JSONArray()
        val result = (0 until segments.length()).mapNotNull { i ->
            val s = segments.getJSONObject(i)
            val t = s.optString("text").trim()
            if (t.isBlank()) return@mapNotNull null
            val start = s.getDouble("start")
            val end = s.getDouble("end")
            require(start.isFinite() && end.isFinite() && start >= 0 && end > start && start < duration && end <= duration + .1) {
                "زمان‌بندی پاسخ آنلاین نامعتبر است؛ دوباره تلاش کنید"
            }
            val w = words(s.optJSONArray("words")).takeIf { valid(it, duration) &&
                SpeechLanguagePolicy.clean(it.joinToString(" ") { word -> word.text }, language) == SpeechLanguagePolicy.clean(t, language) }
                ?: emptyList()
            AsrSegment(t, start, min(end, duration), w)
        }
        require(text.isBlank() || result.isNotEmpty()) { "پاسخ آنلاین زمان‌بندی زیرنویس ندارد؛ دوباره تلاش کنید" }
        return result
    }

    /** Midpoint ownership prevents overlap text accumulating at chunk boundaries. */
    fun owned(segments: List<AsrSegment>, chunk: AudioChunk): List<AsrSegment> = segments.mapNotNull { s ->
        if (s.words.isNotEmpty() && valid(s.words, chunk.uploadEnd - chunk.uploadStart)) {
            val selected = s.words.map { it.copy(start = it.start + chunk.uploadStart, end = it.end + chunk.uploadStart) }
                .filter { (it.start + it.end) / 2 >= chunk.start && (it.start + it.end) / 2 < chunk.end }
            if (selected.isEmpty()) null else AsrSegment(selected.joinToString(" ") { it.text }, selected.first().start, selected.last().end, selected)
        } else {
            val start = s.start + chunk.uploadStart
            val end = s.end + chunk.uploadStart
            if ((start + end) / 2 < chunk.start || (start + end) / 2 >= chunk.end) null
            else s.copy(start = start, end = end, words = emptyList())
        }
    }

    fun httpError(code: Int): String = when (code) {
        401 -> "کلید Groq معتبر نیست؛ کلید را در تنظیمات آنلاین اصلاح کنید"
        403 -> "حساب یا اتصال شما به Groq دسترسی ندارد؛ از حالت آفلاین استفاده کنید"
        413 -> "حجم صوت بیش از حد مجاز Groq است"
        429 -> "سهمیه یا ظرفیت Groq فعلاً تمام شده؛ بعداً تلاش کنید یا آفلاین را انتخاب کنید"
        in 500..599 -> "سرویس Groq موقتاً پاسخ نمی‌دهد؛ دوباره تلاش کنید یا آفلاین را انتخاب کنید"
        else -> "درخواست آنلاین پذیرفته نشد (کد $code)؛ تنظیمات و دسترسی حساب Groq را بررسی کنید"
    }
}
