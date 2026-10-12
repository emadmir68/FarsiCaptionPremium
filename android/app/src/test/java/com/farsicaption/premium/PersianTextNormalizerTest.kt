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
}
