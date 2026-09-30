package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashPromptTest {
    @Test
    fun blankOrAlreadyPromptedDoesNotShow() {
        assertFalse(shouldPromptForCrash(null, null))
        assertFalse(shouldPromptForCrash("  ", null))
        val text = "时间: 2026-09-30\n--- 堆栈 ---\n"
        val fingerprint = crashTextFingerprint(text)
        assertFalse(shouldPromptForCrash(text, fingerprint))
    }

    @Test
    fun newCrashTextPromptsAgain() {
        val first = "boom-1"
        val second = "boom-2"
        assertTrue(shouldPromptForCrash(first, null))
        assertTrue(shouldPromptForCrash(second, crashTextFingerprint(first)))
        assertEquals(crashTextFingerprint(first), crashTextFingerprint(first))
    }
}
