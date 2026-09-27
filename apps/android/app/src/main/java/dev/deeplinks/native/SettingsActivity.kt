package dev.deeplinks.native

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.deeplinks.core.HostStore
import dev.deeplinks.core.resolveFromIntent

/**
 * 设置页（迁移期兼容壳）。
 *
 * 批次 2 起设置 UI 已并入主 Activity 的应用级导航（AppRoute.SETTINGS →
 * [SettingsRoute]），不再由本 Activity 托管。本类只保留为外部入口的兼容壳：
 * 收到 Intent 就转发到 MainActivity 的 settings route 并立即结束自己，
 * 避免设置表现为「另一个 Activity」（独立闪白 / 不同转场）。
 *
 * 应用内入口（任务首页 / 侧栏的设置按钮）一律走 AppNavHost 导航。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = HostStore.load(this).resolveFromIntent(intent)
        val forward = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_START_ROUTE, AppRoute.SETTINGS)
            action = intent.action
            intent.data?.let { data = it }
            host?.putInto(this)
        }
        startActivity(forward)
        finish()
    }
}
