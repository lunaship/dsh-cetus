package dev.deeplinks

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.deeplinks.native.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * debug 包开了 StrictMode.detectNetwork + penaltyDeath。
 * 启动和前后台切换如果在主线程做网络 I/O，进程会死，这个测试就失败。
 * 同进程里无法 force-stop 自己，真进程冷启动由本机 adb 另跑三轮。
 */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {
    @Test
    fun startThreeTimesAndBackgroundThreeTimes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        repeat(3) {
            context.startActivity(launch())
            Thread.sleep(800)
            assertHomeResumed()
        }
        repeat(3) {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            Thread.sleep(400)
            context.startActivity(launch())
            Thread.sleep(800)
            assertHomeResumed()
        }
    }

    private fun assertHomeResumed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var resumed = false
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
        }
        assertTrue("MainActivity must reach RESUMED after launch", resumed)
    }

    private fun launch(): Intent =
        Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
