package dev.deeplinks.native

import dev.deeplinks.native.DshIconSize
import dev.deeplinks.core.tabularNums
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.dshRipple

/**
 * 轮末改动文件卡片（对齐 DSH Web `dsh-client-ui-deliverables` 的 changed-files card）：
 * 单文件标题写「已编辑 文件名」，多文件写总数；右侧增删合计；默认列前 4 个文件，
 * 其余收成「还有 N 个文件」。点文件直达该文件对比，点标题 / 更多打开审查面。
 */
@Composable
internal fun WorkspaceChangesCard(
    summary: WorkspaceChangesSummary,
    onOpen: (fileIndex: Int?) -> Unit,
) {
    // v4 4.2：轮尾第一项。描边容器：标题「改了 N 个文件 +n −m」，最多 3 行文件（等宽路径），
    // 末行「查看全部改动 ›」品牌色。整卡不填底色。
    val shape = RoundedCornerShape(DshRadius.container)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Dsh.outline, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DshTouch.min)
                .clickable(indication = dshRipple(), interactionSource = null) { onOpen(null) }
                .semantics {
                    role = Role.Button
                    contentDescription = ChangesL.cardTitle(summary)
                }
                .padding(horizontal = DshSpace.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ChangesL.changedFiles.format(summary.total),
                color = Dsh.labelPrimary,
                style = DshType.bodyStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DiffStat(summary.added, summary.deleted)
        }
        summary.files.take(3).forEachIndexed { index, file ->
            HorizontalDivider(thickness = 1.dp, color = Dsh.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = DshTouch.min)
                    .clickable(indication = dshRipple(), interactionSource = null) { onOpen(index) }
                    .semantics {
                        role = Role.Button
                        contentDescription = file.display
                    }
                    .padding(horizontal = DshSpace.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    file.display,
                    color = Dsh.labelPrimary,
                    style = DshType.supporting.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(DshSpace.s8))
                when {
                    file.binary -> Text("BIN", color = Dsh.labelSecondary, style = DshType.caption)
                    file.oversized -> Text("—", color = Dsh.labelSecondary, style = DshType.caption)
                    else -> DiffStat(file.added, file.deleted)
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Dsh.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DshTouch.min)
                .clickable(role = Role.Button) { onOpen(null) }
                .padding(horizontal = DshSpace.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ChangesL.viewAllChanges, color = Dsh.brand400, style = DshType.supporting, modifier = Modifier.weight(1f))
            Icon(ChevronRightOutline16, contentDescription = null, tint = Dsh.brand400, modifier = Modifier.size(DshIconSize.xs))
        }
    }
}

/** 一行改动文件：文件名 + 目录（弱）+ 行数 / 降级说明。卡片与审查面共用。 */
@Composable
internal fun ChangedFileRow(
    file: ChangedFile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    startPadding: Dp = 38.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DshTouch.min)
            .clickable(indication = dshRipple(), interactionSource = null, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = file.display
            }
            .padding(start = startPadding, end = DshSpace.s12, top = DshSpace.s8, bottom = DshSpace.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                file.name,
                color = Dsh.labelPrimary,
                style = DshType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.directory.isNotEmpty()) {
                Spacer(Modifier.width(DshSpace.s8))
                Text(
                    file.directory,
                    color = Dsh.labelTertiary,
                    style = DshType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(DshSpace.s8))
        when {
            file.binary -> Text("BIN", color = Dsh.labelTertiary, style = DshType.microMedium)
            file.oversized -> Text("—", color = Dsh.labelTertiary, style = DshType.microMedium)
            else -> DiffStat(file.added, file.deleted)
        }
    }
}

/** `+12 −3`：增绿删红，等宽数字；为 0 的一侧省略。 */
@Composable
internal fun DiffStat(added: Int, deleted: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val style = DshType.caption.copy(fontFamily = FontFamily.Monospace).tabularNums()
        if (added > 0) Text("+$added", color = Dsh.ok, style = style)
        if (added > 0 && deleted > 0) Spacer(Modifier.width(DshSpace.s8))
        if (deleted > 0) Text("−$deleted", color = Dsh.err, style = style)
    }
}
