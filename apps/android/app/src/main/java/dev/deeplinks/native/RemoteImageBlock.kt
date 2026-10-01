package dev.deeplinks.native

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.deeplinks.core.Dsh
import dev.deeplinks.core.DshType
import dev.deeplinks.core.L
import dev.deeplinks.core.dshRipple
import dev.deeplinks.native.util.copiedNeedsAppToast

/**
 * 对话中的远程图片（第 2 步 B3）。
 *
 * 状态机：
 * ```
 * 占位 ──点按──▶ 加载中 ──成功──▶ 已加载 ──点按──▶ 浏览器打开
 *                  └─失败──▶ 失败（点按重试）──▶ 加载中
 * 任意状态长按 ▶ 复制链接
 * ```
 *
 * 设计约束：
 * - 远程图片默认不加载（防提示词注入外泄内容与 IP），点按是用户的显式同意；
 * - 不显示完整 URL，只显示域名；复制链接走系统剪贴板（Android 12 及以下弹 Toast）；
 * - 可点区域是 Button 角色并带 onClickLabel / onLongClickLabel，TalkBack 读得出动作。
 */
@Composable
internal fun RemoteImageBlock(url: String, modifier: Modifier = Modifier) {
    val policy = LocalRemoteImagePolicy.current
    // requested：0 = 占位，1 = 首次请求，>1 = 第 N 次重试（key 重建请求并绕缓存）。
    var requested by remember(url) {
        mutableIntStateOf(if (policy.shouldLoad(url)) 1 else 0)
    }
    val context = LocalContext.current
    val host = remember(url) { runCatching { Uri.parse(url).host }.getOrNull().orEmpty() }

    fun copyLink() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("image url", url))
        if (copiedNeedsAppToast(Build.VERSION.SDK_INT)) {
            Toast.makeText(context, L.imageLinkCopied, Toast.LENGTH_SHORT).show()
        }
    }

    fun openInBrowser() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (requested == 0) {
            ImageActionCard(
                onClick = { requested = 1; policy.allow(url) },
                onClickLabel = L.a11yLoadImage,
                onLongClick = ::copyLink,
                onLongClickLabel = L.a11yCopyImageLink,
                contentDescription = if (host.isBlank()) L.tapToLoadImage else L.a11yImageFrom.format(host),
                minHeight = 56.dp,
            ) {
                Icon(
                    imageVector = ImageOutline16,
                    contentDescription = null,
                    tint = Dsh.labelSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(DshSpace.s8))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = L.tapToLoadImage,
                        color = Dsh.labelSecondary,
                        style = DshType.supporting,
                    )
                    if (host.isNotBlank()) {
                        Text(
                            text = host,
                            color = Dsh.labelTertiary,
                            style = DshType.microRelaxed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            key(requested) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(url)
                        // 重试时强制重新走网络：磁盘/内存里可能留着上一轮的失败空响应。
                        .memoryCachePolicy(if (requested > 1) CachePolicy.DISABLED else CachePolicy.ENABLED)
                        .diskCachePolicy(if (requested > 1) CachePolicy.DISABLED else CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(DshRadius.container))
                        .background(Dsh.bgCard)
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = L.a11yOpenInBrowser,
                            onLongClickLabel = L.a11yCopyImageLink,
                            onClick = { openInBrowser() },
                            onLongClick = { copyLink() },
                        ),
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Dsh.labelSecondary,
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                    error = {
                        ImageActionCard(
                            onClick = { requested += 1 },
                            onClickLabel = L.a11yRetryLoadImage,
                            onLongClick = ::copyLink,
                            onLongClickLabel = L.a11yCopyImageLink,
                            contentDescription = L.imageLoadFailed,
                            minHeight = 56.dp,
                        ) {
                            Icon(
                                imageVector = ImageOutline16,
                                contentDescription = null,
                                tint = Dsh.labelSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(DshSpace.s8))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = L.imageLoadFailed,
                                    color = Dsh.labelSecondary,
                                    style = DshType.supporting,
                                )
                                if (host.isNotBlank()) {
                                    Text(
                                        text = host,
                                        color = Dsh.labelTertiary,
                                        style = DshType.microRelaxed,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 图片占位 / 失败卡：56dp 最小高度、12dp 内边距、1dp 发丝边框、20dp 图标。
 * 点按与长按都是 Button 角色并带动作标签，TalkBack 能读出来。
 */
@Composable
private fun ImageActionCard(
    onClick: () -> Unit,
    onClickLabel: String,
    onLongClick: () -> Unit,
    onLongClickLabel: String,
    contentDescription: String,
    minHeight: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(DshRadius.container))
            .background(Dsh.bgSubtle)
            .combinedClickable(
                interactionSource = interaction,
                indication = dshRipple(),
                role = Role.Button,
                onClickLabel = onClickLabel,
                onLongClickLabel = onLongClickLabel,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = DshSpace.s12, vertical = DshSpace.s12)
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
