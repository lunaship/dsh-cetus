package dev.deeplinks.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySafeDiagnosticsTest {
    private val lines = mutableListOf<String>()

    @After
    fun tearDown() {
        PrivacySafeDiagnostics.install(false)
        PrivacySafeDiagnostics.resetForTest()
    }

    @Test
    fun silentWhenDisabled() {
        PrivacySafeDiagnostics.writer = { _, _, m -> lines += m }
        PrivacySafeDiagnostics.install(false)
        PrivacySafeDiagnostics.event(PrivacySafeDiagnostics.Area.Goal, PrivacySafeDiagnostics.Op.Pause, ok = true)
        PrivacySafeDiagnostics.streamBatch(10, 2)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun emitsOnlyEnumsAndCounts() {
        PrivacySafeDiagnostics.writer = { _, _, m -> lines += m }
        PrivacySafeDiagnostics.install(true)
        PrivacySafeDiagnostics.event(PrivacySafeDiagnostics.Area.Schedule, PrivacySafeDiagnostics.Op.Load, ok = false, count = 3)
        PrivacySafeDiagnostics.streamBatch(10, 2)
        PrivacySafeDiagnostics.streamBatch(10, 2)
        assertEquals(listOf("schedule load ok=false count=3", "stream batch n=1 received=10 applied=2"), lines)
        assertTrue(lines.all { Regex("[a-z0-9 =]+").matches(it) })
    }
}
