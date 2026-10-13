package com.farsicaption.premium

/** Language-specific text handling must never infer language from Arabic-script characters. */
object SpeechLanguagePolicy {
    fun whisperLanguage(selected: String): String = if (selected == "ar") "ar" else "fa"

    fun clean(raw: String, selected: String, corrections: Map<String, String> = emptyMap()): String {
        val stripped = raw
            .replace(Regex("""\[(music|موسیقی|silence|آهنگ|applause|تشویق)[^]]*]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\((music|موسیقی|silence|آهنگ|applause|تشویق)[^)]*\)""", RegexOption.IGNORE_CASE), " ")
            .trim()
        val text = if (selected == "ar") stripped else PersianTextNormalizer.normalize(stripped, corrections)
        return if (text.lowercase().trim('[', ']', ' ') in
            setOf("music", "موسیقی", "آهنگ", "silence", "applause", "تشویق")) "" else text
    }
}
