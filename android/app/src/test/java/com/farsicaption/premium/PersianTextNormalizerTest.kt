package com.farsicaption.premium
import org.junit.Assert.*
import org.junit.Test
class PersianTextNormalizerTest {
    @Test fun foldsYehAndKaf() { assertEquals("یکی", PersianTextNormalizer.normalize("يكي")) }
    @Test fun joinsKnownVerbPrefix() { assertEquals("می‌روم نمی‌خواهم", PersianTextNormalizer.normalize("می روم نمی خواهم")) }
    @Test fun neverGuessesWords() { assertEquals("ده مایسا ده؟", PersianTextNormalizer.normalize("ده مایسا ده؟")) }
    @Test fun retainsColloquialSpeech() { assertEquals("نگاه جوون", PersianTextNormalizer.normalize("نگاه جوون")) }
    @Test fun doesNotJoinAmbiguousCompounds() { assertEquals("هم کار و هم تفریح", PersianTextNormalizer.normalize("هم کار و هم تفریح")) }
    @Test fun retainsDistinctLetters() { assertEquals("آ أ ؤ ئ", PersianTextNormalizer.normalize("آ أ ؤ ئ")) }
    @Test fun retainsMonthName() { assertEquals("ماه می بود", PersianTextNormalizer.normalize("ماه می بود")) }
    @Test fun retainsLatinText() { assertEquals("Android 13، خوب؟", PersianTextNormalizer.normalize("Android 13, خوب ?")) }
    @Test fun joinsPluralSuffixes() { assertEquals("کتاب‌ها خانه‌های", PersianTextNormalizer.normalize("کتاب ها خانه های")) }
    @Test fun joinsComparativeSuffixes() { assertEquals("بزرگ‌تر بهترین", PersianTextNormalizer.normalize("بزرگ تر بهترین")) }
    @Test fun suffixRulesDoNotTouchLatinUrlsOrNumbers() { assertEquals("Android ها https://example.com/a ها 13 ها", PersianTextNormalizer.normalize("Android ها https://example.com/a ها 13 ها")) }
    @Test fun suffixNormalizationIsIdempotent() { val x = PersianTextNormalizer.normalize("کتاب ها خانه های بزرگ تر"); assertEquals(x, PersianTextNormalizer.normalize(x)) }
    @Test fun normalizesExistingHalfSpace() { assertEquals("کتاب‌ها", PersianTextNormalizer.normalize("کتاب ‌ ها")) }
    @Test fun isIdempotent() { val x = PersianTextNormalizer.normalize("  مي روم , كتاب ‌ ها؟  "); assertEquals(x, PersianTextNormalizer.normalize(x)) }
}
