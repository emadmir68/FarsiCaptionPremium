package com.farsicaption.premium
import org.junit.Assert.*
import org.junit.Test
class SpeechLanguagePolicyTest {
    @Test fun languagesAreExplicit() { assertEquals("fa", SpeechLanguagePolicy.whisperLanguage("fa")); assertEquals("ar", SpeechLanguagePolicy.whisperLanguage("ar")) }
    @Test fun legacyAutoDefaultsToPersian() { assertEquals("fa", SpeechLanguagePolicy.whisperLanguage("auto")) }
    @Test fun preservesArabicSpellingAndDiacritics() { assertEquals("عَلَيْكُم يا كريم؟", SpeechLanguagePolicy.clean("عَلَيْكُم يا كريم؟", "ar")) }
    @Test fun normalizesPersianOnly() { assertEquals("یکی", SpeechLanguagePolicy.clean("يكي", "fa")) }
    @Test fun removesOnlyExplicitMarkers() { assertEquals("", SpeechLanguagePolicy.clean("[music] ♪ (سکوت)", "fa")); assertEquals("", SpeechLanguagePolicy.clean("[موسيقى]", "ar")) }
    @Test fun retainsSpokenMusicWord() { assertEquals("موسیقی خوب است", SpeechLanguagePolicy.clean("موسیقی خوب است", "fa")) }
    @Test fun arabicCannotChangeNextPersianRun() { SpeechLanguagePolicy.clean("يكي", "ar"); assertEquals("یکی", SpeechLanguagePolicy.clean("يكي", "fa")) }
}
