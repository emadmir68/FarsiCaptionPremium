package com.farsicaption.premium
object SpeechLanguagePolicy {
    fun whisperLanguage(selected: String): String = if (selected == "ar") "ar" else "fa"
    fun clean(raw: String, selected: String): String {
        val marker = "(?:music|موسیقی|موسيقى|silence|سکوت|صمت|آهنگ|applause|تشویق|تصفيق)"
        val stripped = raw.replace(Regex("\\[\\s*$marker\\s*]|\\(\\s*$marker\\s*\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[♪♫]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (selected == "ar") stripped else PersianTextNormalizer.normalize(stripped)
    }
}
