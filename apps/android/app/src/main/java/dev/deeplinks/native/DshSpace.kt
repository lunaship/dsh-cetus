package dev.deeplinks.native

import androidx.compose.ui.unit.dp

/**
 * 间距刻度（docs/visual-rules.md 第七节）。
 *
 * DSH 没有把间距做成 --dsw-* token；这 9 档取自 DSH Web 实际写下的 padding / gap：
 * 2/4/6/8/12/16/24/32 占它全部间距声明的八成，20 是本端页面节奏补的一档。
 * 名字就是数值，刻意不起语义名：约束在于「只有这几档」，不在于间接层。
 *
 * padding / spacedBy / PaddingValues / Spacer 里的裸 dp 由 DshSpacingUsageTest 按文件预算只降不升；
 * 10、14 这类刻度外的值要落到相邻一档，是逐处的视觉决定，不做批量替换。
 */
object DshSpace {
    val s2 = 2.dp
    val s3 = 3.dp
    val s4 = 4.dp
    val s6 = 6.dp
    val s8 = 8.dp
    val s10 = 10.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s20 = 20.dp
    val s24 = 24.dp
    val s32 = 32.dp

    /** Compact 页面水平边距（visual-rules 第一节）。 */
    val pageGutter = s16

    /** 组与组之间的留白：空白留在组间，不在每个元素周围均匀撒。 */
    val sectionGap = s24
}
