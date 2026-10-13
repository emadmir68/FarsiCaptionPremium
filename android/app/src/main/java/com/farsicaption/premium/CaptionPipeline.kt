package com.farsicaption.premium
internal data class AsrSegment(val text: String, val start: Double, val end: Double, val words: List<TimedWord> = emptyList())
internal object CaptionPipeline {
    fun build(segments: List<AsrSegment>, duration: Double, maxWords: Int, language: String): List<CaptionItem> {
        if (!duration.isFinite() || duration <= 0) return emptyList()
        val accepted = mutableListOf<AsrSegment>()
        segments.forEach { s ->
            if (!s.start.isFinite() || !s.end.isFinite() || s.end <= s.start || s.start >= duration || s.end <= 0) return@forEach
            val clean = SpeechLanguagePolicy.clean(s.text, language)
            if (clean.isBlank()) return@forEach
            val previous = accepted.lastOrNull()
            if (previous != null && kotlin.math.abs(previous.start - s.start) < 0.01 && s.end >= previous.end &&
                (clean == previous.text || clean.startsWith(previous.text + " "))) accepted.removeAt(accepted.lastIndex)
            accepted += s.copy(text = clean, start = s.start.coerceAtLeast(0.0), end = s.end.coerceAtMost(duration))
        }
        val out = mutableListOf<CaptionItem>()
        accepted.forEach { s ->
            val start = maxOf(s.start, out.lastOrNull()?.end ?: 0.0)
            if (s.end <= start) return@forEach
            val words = s.words
            // Missing/invalid alignment retains the native interval; never invent word timings.
            val aligned = words.isNotEmpty() && words.all { it.start.isFinite() && it.end.isFinite() && it.start >= s.start && it.end <= s.end && it.end > it.start } &&
                words.zipWithNext().all { (a, b) -> a.end <= b.start } &&
                SpeechLanguagePolicy.clean(words.joinToString(" ") { it.text }, language) == s.text
            if (aligned) words.chunked(maxWords.coerceIn(2, 7)).forEach { chunk ->
                val text = SpeechLanguagePolicy.clean(chunk.joinToString(" ") { it.text }, language)
                val a = maxOf(chunk.first().start, start, out.lastOrNull()?.end ?: 0.0)
                val b = chunk.last().end
                if (text.isNotBlank() && b > a) out += CaptionItem(text, a, b, chunk)
            } else out += CaptionItem(s.text, start, s.end)
        }
        return out
    }
}
