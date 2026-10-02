package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目标轮次折叠行把 [goalRoundProgress] 的字符串（如 "1/256"）填进 goalRoundLabel。
 * 文案必须是 %s；goalRounds / goalRoundsOf 的调用方传的是 Int，保持 %d。
 */
class GoalRoundLabelTest {
    @Test
    fun goalRoundLabelFormatsProgressStrings() {
        assertEquals("第 1/256 轮", label(DshStringsZh, "1/256"))
        assertEquals("Round 1/256", label(DshStringsEn, "1/256"))
        assertEquals("第 3 / 8 轮", label(DshStringsZh, "3 / 8"))
        assertEquals("Round 3 / 8", label(DshStringsEn, "3 / 8"))
    }

    @Test
    fun goalRoundLabelSkipsNullProgress() {
        assertNull(labelOrNull(DshStringsZh, null))
        assertNull(labelOrNull(DshStringsEn, null))
    }

    @Test
    fun goalRoundsStayIntegerFormats() {
        assertEquals("第 3 轮", DshStringsZh.goalRounds.format(3))
        assertEquals("Round 3", DshStringsEn.goalRounds.format(3))
        assertEquals("第 3 / 8 轮", DshStringsZh.goalRoundsOf.format(3, 8))
        assertEquals("Round 3 of 8", DshStringsEn.goalRoundsOf.format(3, 8))
    }

    /** 与 MessageItem 折叠行同一条格式化路径。 */
    private fun label(strings: DshStrings, progress: String): String =
        strings.goalRoundLabel.format(progress)

    /** progress 为 null 时折叠行不拼接轮次，也不调用 format。 */
    private fun labelOrNull(strings: DshStrings, progress: String?): String? =
        progress?.let { strings.goalRoundLabel.format(it) }
}
