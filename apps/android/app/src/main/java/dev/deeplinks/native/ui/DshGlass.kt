package dev.deeplinks.native.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.deeplinks.core.Dsh

/**
 * 旧玻璃材质入口，v4 起只画实色（docs/visual-rules.md：无模糊、无半透明、无阴影）。
 * 签名保留给尚未迁移的调用点，模块迁移完成后随 R4 删除。
 * [backdrop] / [highlightBoost] 不再生效。
 */
enum class DshGlassTier {
    Control,
    Floating,

    @Deprecated("全宽导航条已下线")
    Navigation,
}

enum class DshGlassSurface { Standard, Strong }

/** 省电或动画时长为 0 时为 true；预览（layoutlib）恒为 false。 */
@Composable
fun rememberDshReduceTransparency(): State<Boolean> {
    val state = remember { mutableStateOf(false) }
    if (LocalInspectionMode.current) return state
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val resolver = context.contentResolver
        fun read(): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val powerSave = powerManager?.isPowerSaveMode ?: false
            val animatorScale = Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            return powerSave || animatorScale == 0f
        }

        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) state.value = read()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                state.value = read()
            }
        }
        val powerFilter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(powerSaveReceiver, powerFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(powerSaveReceiver, powerFilter)
        }

        val animatorObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.value = read()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            animatorObserver,
        )

        state.value = read()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            context.unregisterReceiver(powerSaveReceiver)
            resolver.unregisterContentObserver(animatorObserver)
        }
    }
    return state
}

/** 实色表面：Control 档用容器色 surface1，其余用 [base]。 */
@Composable
fun Modifier.dshGlass(
    tier: DshGlassTier,
    backdrop: LayerBackdrop?,
    shape: Shape,
    base: Color = Dsh.bgBase,
    surface: DshGlassSurface = DshGlassSurface.Standard,
    highlightBoost: Float = 0f,
): Modifier {
    val color = if (tier == DshGlassTier.Control) Dsh.surface1 else base
    return this.background(color, shape)
}

@Deprecated("全宽导航条已下线")
val DshGlassFullWidthShape: Shape = RectangleShape
