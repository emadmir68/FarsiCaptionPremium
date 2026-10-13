package com.farsicaption.premium

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechLanguagePolicyTest {
    @Test fun persianKeepsPersianNormalization() {
        assertEquals("می‌روم", SpeechLanguagePolicy.clean("مي روم", "fa"))
        assertEquals("fa", SpeechLanguagePolicy.whisperLanguage("fa"))
    }

    @Test fun arabicPreservesArabicSpellingAndDiacritics() {
        val arabic = "إِنَّ اللَّهَ غَفُورٌ رَحِيمٌ"
        assertEquals(arabic, SpeechLanguagePolicy.clean(arabic, "ar"))
        assertEquals("ar", SpeechLanguagePolicy.whisperLanguage("ar"))
    }

    @Test fun arabicNeverAppliesPersianLearnedCorrections() {
        assertEquals("سلام", SpeechLanguagePolicy.clean("سلام", "ar", mapOf("سلام" to "درود")))
        assertEquals("درود", SpeechLanguagePolicy.clean("سلام", "fa", mapOf("سلام" to "درود")))
    }

    @Test fun unknownLanguageFallsBackToPersianRatherThanArabic() {
        assertEquals("fa", SpeechLanguagePolicy.whisperLanguage("auto"))
        assertEquals("fa", SpeechLanguagePolicy.whisperLanguage(""))
    }

    @Test fun musicMarkersAreRemovedForBothLanguages() {
        assertEquals("", SpeechLanguagePolicy.clean("[music]", "fa"))
        assertEquals("", SpeechLanguagePolicy.clean("[music]", "ar"))
    }
}
