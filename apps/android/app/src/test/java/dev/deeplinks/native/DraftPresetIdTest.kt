package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N3：草稿态模式行显示的预设 id。选择器写入 pendingAgentPreset 后这里立刻反映新值；
 * 没选过时回落全局默认。
 */
class DraftPresetIdTest {

    @Test
    fun pendingSelectionWins() {
        assertEquals("ptc", draftPresetId("ptc", "standard"))
    }

    @Test
    fun fallsBackToDefaultWhenNothingPicked() {
        assertEquals("standard", draftPresetId(null, "standard"))
        assertEquals("standard", draftPresetId("", "standard"))
        assertEquals("standard", draftPresetId("   ", "standard"))
    }

    @Test
    fun selectingASecondPresetReturnsTheNewValue() {
        assertEquals("minimal", draftPresetId("minimal", "standard"))
    }
}
