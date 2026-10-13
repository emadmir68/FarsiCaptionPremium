package com.farsicaption.premium

import org.junit.Assert.assertEquals
import org.junit.Test

class PersianTextNormalizerTest {

    @Test
    fun joinsCommonPersianCompound() {
        assertEquals(
            "خدمت شما همکاران محترم",
            PersianTextNormalizer.normalize("خدمت شما هم کاران محترم"),
        )
    }

    @Test
    fun fixesPersianVerbHalfSpace() {
        assertEquals(
            "این کار انجام می‌شود",
            PersianTextNormalizer.normalize("این کار انجام می شود"),
        )
    }

    @Test
    fun normalizesArabicCharacters() {
        assertEquals(
            "یک همکاری",
            PersianTextNormalizer.normalize("يك هم كاري"),
        )
    }

    @Test
    fun customCorrectionWins() {
        assertEquals(
            "نگاه جوان",
            PersianTextNormalizer.normalize(
                "نگاه جوون",
                mapOf("نگاه جوون" to "نگاه جوان"),
            ),
        )
    }
    @Test
    fun normalizesPunctuationAndArabicForms() {
        assertEquals(
            "این یک تست، خوب است؟",
            PersianTextNormalizer.normalize("اين يك تست, خوب است?"),
        )
    }

    @Test
    fun keepsCorrectSeparatedPhrase() {
        assertEquals(
            "در مورد این موضوع",
            PersianTextNormalizer.normalize("در مورد این موضوع"),
        )
    }


    @Test
    fun preservesPersianDistinctLetters() {
        assertEquals(
            "آموزش آزاد و مؤثر",
            PersianTextNormalizer.normalize("آموزش آزاد و مؤثر"),
        )
    }

    @Test
    fun correctionsDoNotModifySubstringsOfWords() {
        assertEquals(
            "کتابخانه دفتر",
            PersianTextNormalizer.normalize(
                "کتابخانه کتاب",
                mapOf("کتاب" to "دفتر"),
            ),
        )
    }

    @Test
    fun correctionsRespectPersianPunctuation() {
        assertEquals(
            "«درست»؛ درست!",
            PersianTextNormalizer.normalize(
                "«غلط»؛ غلط!",
                mapOf("غلط" to "درست"),
            ),
        )
    }

    @Test
    fun preservesHalfSpaceAndLatinText() {
        assertEquals(
            "می‌روم API",
            PersianTextNormalizer.normalize("می‌روم API"),
        )
    }

    @Test
    fun cumulativeTranscriptKeepsOnlyNewWords() {
        val engine = LocalCaptionEngineForTextTest
        assertEquals("خوبی؟", engine.incremental("سلام", "سلام خوبی؟"))
        assertEquals("چه خبر", engine.incremental("سلام خوبی؟", "سلام خوبی؟ چه خبر"))
    }

    @Test
    fun independentRepeatedSpeechIsNotMistakenForCumulativeGrowth() {
        val engine = LocalCaptionEngineForTextTest
        assertEquals("سلام", engine.incremental("سلام", "سلام"))
        assertEquals("سلام دوست", engine.incremental("خوبی", "سلام دوست"))
    }

}


private object LocalCaptionEngineForTextTest {
    fun incremental(previous: String, current: String): String {
        val prevTokens = previous.trim().split(Regex("""\\s+""")).filter { it.isNotBlank() }
        val currentTokens = current.trim().split(Regex("""\\s+""")).filter { it.isNotBlank() }
        if (prevTokens.isEmpty() || currentTokens.size <= prevTokens.size) return current
        fun key(token: String) = token.replace("‌", "").trim('،', '؛', ':', '.', '!', '؟', '?', '«', '»', '(', ')')
        if (!prevTokens.indices.all { key(prevTokens[it]) == key(currentTokens[it]) }) return current
        return currentTokens.drop(prevTokens.size).joinToString(" ")
    }
}
