package com.farsicaption.premium
import java.text.Normalizer
/** Typography only: never guess the intended vocabulary. */
object PersianTextNormalizer {
    fun normalize(raw: String): String {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace('ي', 'ی').replace('ى', 'ی').replace('ك', 'ک').replace("ـ", "")
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
            .replace(Regex("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]"), " ")
            .replace(',', '،').replace('?', '؟').replace(';', '؛')
            .replace(Regex("\\s+"), " ").trim()
        val verb = "(?:روم|روی|رود|رویم|روید|روند|کنم|کنی|کند|کنیم|کنید|کنند|شود|شوم|شوی|شویم|شوید|شوند|خواهم|خواهی|خواهد|خواهیم|خواهید|خواهند|توانم|توانی|تواند|توانیم|توانید|توانند|گویم|گویی|گوید|گوییم|گویید|گویند)"
        text = text.replace(Regex("(?<![\\p{L}\\p{N}\\u200C])(ن?می) +($verb)(?![\\p{L}\\p{N}\\u200C])")) {
            it.groupValues[1] + "‌" + it.groupValues[2]
        }
        // Join only high-confidence Persian suffixes emitted as separate ASR tokens.
        // Requiring a Persian-script stem avoids touching Latin text, URLs and numbers.
        text = text.replace(Regex("([\\p{L}&&[\\u0600-\\u06FF]]+) +(ها|های|تر|ترین)(?![\\p{L}\\p{N}\\u200C])")) {
            it.groupValues[1] + "‌" + it.groupValues[2]
        }
        return text.replace(Regex(" *‌+ *"), "‌").replace(Regex("\\s+([،؛:,.!?؟])"), "$1")
            .replace(Regex("([،؛!?؟])([^\\s»)])"), "$1 $2").trim()
    }
}
