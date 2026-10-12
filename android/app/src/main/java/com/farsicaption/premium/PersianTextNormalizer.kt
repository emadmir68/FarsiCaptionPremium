package com.farsicaption.premium

import java.text.Normalizer

object PersianTextNormalizer {

    private val fixedPhrases = linkedMapOf(
        "هم کاران" to "همکاران",
        "هم کار" to "همکار",
        "هم کاری" to "همکاری",
        "هم کلاسی" to "همکلاسی",
        "هم وطن" to "هموطن",
        "هم زبان" to "هم‌زبان",
        "هم زمان" to "هم‌زمان",
        "به خاطر" to "به‌خاطر",
        "به صورت" to "به‌صورت",
        "به عنوان" to "به‌عنوان",
        "می باشد" to "می‌باشد",
        "می شود" to "می‌شود",
        "نمی شود" to "نمی‌شود",
        "می کنم" to "می‌کنم",
        "می کنی" to "می‌کنی",
        "می کند" to "می‌کند",
        "می کنیم" to "می‌کنیم",
        "می کنید" to "می‌کنید",
        "می کنند" to "می‌کنند",
        "نمی کنم" to "نمی‌کنم",
        "نمی کند" to "نمی‌کند",
        "نمی کنند" to "نمی‌کنند",
        "می خواهم" to "می‌خواهم",
        "می خواهی" to "می‌خواهی",
        "می خواهد" to "می‌خواهد",
        "می خواهیم" to "می‌خواهیم",
        "می خواهید" to "می‌خواهید",
        "می خواهند" to "می‌خواهند",
        "می توان" to "می‌توان",
        "می تواند" to "می‌تواند",
        "می توانند" to "می‌توانند",
        "نمی توان" to "نمی‌توان",
        "نمی تواند" to "نمی‌تواند",
        "نمی توانند" to "نمی‌توانند",
        "می روم" to "می‌روم",
        "می روی" to "می‌روی",
        "می رود" to "می‌رود",
        "می رویم" to "می‌رویم",
        "می روید" to "می‌روید",
        "می روند" to "می‌روند",
        "نمی روم" to "نمی‌روم",
        "نمی رود" to "نمی‌رود",
        "می گویم" to "می‌گویم",
        "می گویی" to "می‌گویی",
        "می گوید" to "می‌گوید",
        "می گوییم" to "می‌گوییم",
        "می گویید" to "می‌گویید",
        "می گویند" to "می‌گویند",
        "نمی گوید" to "نمی‌گوید"
    )

    private val suffixes = listOf("ها", "های", "تر", "ترین")

    fun normalize(
        raw: String,
        customCorrections: Map<String, String> = emptyMap(),
    ): String {
        if (raw.isBlank()) return raw

        var text = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace('ة', 'ه')
            .replace('ۀ', 'ه')
            .replace('ؤ', 'و')
            .replace(Regex("""[آأإٱ]"""), "ا")
            .replace("ـ", "")
            .replace(Regex("""[\u064B-\u065F\u0670]"""), "")
            .replace(Regex("""[\u200E\u200F\u202A-\u202E\u2066-\u2069]"""), "")
            .replace(Regex("""[\u00A0\u2000-\u200A\u202F\u205F\u3000]"""), " ")
            .replace(',', '،')
            .replace('?', '؟')
            .replace(';', '؛')
            .replace(Regex("""\s+"""), " ")
            .trim()

        val combined = LinkedHashMap<String, String>()
        customCorrections.entries
            .sortedByDescending { it.key.length }
            .forEach { combined[it.key] = it.value }
        fixedPhrases.entries
            .sortedByDescending { it.key.length }
            .forEach { combined.putIfAbsent(it.key, it.value) }

        combined.forEach { (wrong, right) ->
            if (wrong.isNotBlank() && wrong != right) {
                text = text.replace(wrong, right)
            }
        }

        text = text.replace(
            Regex("""(^|[\s،؛:«(])نمی\s+([\u0600-\u06FF‌]+)""")
        ) { m ->
            m.groupValues[1] + "نمی‌" + m.groupValues[2]
        }

        text = text.replace(
            Regex("""(^|[\s،؛:«(])می\s+([\u0600-\u06FF‌]+)""")
        ) { m ->
            m.groupValues[1] + "می‌" + m.groupValues[2]
        }

        suffixes.forEach { suffix ->
            text = text.replace(
                Regex("""([\u0600-\u06FF]+)\s+$suffix(?=\s|$|[،؛.!؟])""")
            ) { m ->
                m.groupValues[1] + "‌" + suffix
            }
        }

        text = text
            .replace(Regex("""\s+([،؛:,.!?؟])""")) { m ->
                m.groupValues[1]
            }
            .replace(Regex("""([،؛:!?؟])([^\s»)])""")) { m ->
                m.groupValues[1] + " " + m.groupValues[2]
            }
            .replace(Regex("""\s+"""), " ")
            .trim()

        return text
    }
}
