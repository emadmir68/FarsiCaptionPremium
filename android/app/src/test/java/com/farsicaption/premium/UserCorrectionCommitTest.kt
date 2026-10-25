package com.farsicaption.premium

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserCorrectionCommitTest {
    @Test fun meaningfulEditCanBeLearned() {
        assertTrue(isMeaningfulCorrection("می روم", "می‌روم"))
    }

    @Test fun unchangedOrIncompleteEditIsNotLearned() {
        assertFalse(isMeaningfulCorrection("سلام", "سلام"))
        assertFalse(isMeaningfulCorrection("سلام", ""))
    }

    @Test fun oversizedEditIsNotLearned() {
        assertFalse(isMeaningfulCorrection("الف".repeat(141), "ب"))
    }
}
