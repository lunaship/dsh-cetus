package dev.deeplinks.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient

/** 应用入口：深色模式跟随系统；界面语言从本地缓存初始化。 */
class DshApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        if (dev.deeplinks.BuildConfig.DEBUG) {
            // 只在 debug：主线程网络 I/O 直接崩，避免再出现冷启动闪退却没有现场。
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectNetwork()
                    .penaltyDeath()
                    .build(),
            )
        }
        // K0：最先装崩溃记录，越早越好（后面的初始化万一崩了也要留证据）。
        CrashRecorder.install(this)
        PrivacySafeDiagnostics.install(dev.deeplinks.BuildConfig.DEBUG)
        val app = this
        Thread({
            dev.deeplinks.core.UpdateChecker.checkBlocking(app, dev.deeplinks.BuildConfig.VERSION_NAME)
        }, "update-check").apply { isDaemon = true }.start()
        // S7：启动打点从进程最早期开始。
        StartupTrace.markStart()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        LocaleManager.init(this)
        ThemeManager.init(this)
        FontScaleManager.init(this)
        watchNetwork()
    }

    /**
     * 网络变化让自动选路重新探测局域网（RFC §7.2 第 1 条）。
     *
     * S5：只有默认网络**真正切换**（handle 变了 / onLost）才清空连接池；同一张网只是 IP 变了
     * 只作废选路缓存。注册回调时系统立刻回调一次 onAvailable，此时只登记 handle，不清池。
     * S4：顺带刷新「当前默认网络能不能走局域网」，供选路跳过蜂窝下的局域网探测。
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        NetworkTransport.refresh(cm)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val action = networkChangeAction(defaultHandle, network.networkHandle, defaultAddrs, defaultAddrs)
                    defaultHandle = network.networkHandle
                    defaultAddrs = emptySet()
                    applyNetworkAction(action, cm)
                }

                override fun onLost(network: Network) {
                    val action = networkChangeAction(defaultHandle, null, defaultAddrs, emptySet())
                    defaultHandle = null
                    defaultAddrs = emptySet()
                    applyNetworkAction(action, cm)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val addrs = linkProperties.linkAddresses.mapNotNull { it.address?.hostAddress }.toSet()
                    val action = networkChangeAction(defaultHandle, network.networkHandle, defaultAddrs, addrs)
                    defaultAddrs = addrs
                    applyNetworkAction(action, cm)
                }
            })
        }
    }

    private var defaultHandle: Long? = null
    private var defaultAddrs: Set<String> = emptySet()

    private fun applyNetworkAction(action: NetworkChangeAction, cm: ConnectivityManager) {
        when (action) {
            NetworkChangeAction.ResetPool -> HostHttp.onNetworkChanged()
            NetworkChangeAction.InvalidateRoutes -> HostHttp.onRoutesChanged()
            NetworkChangeAction.None -> Unit
        }
        NetworkTransport.refresh(cm)
        ConnectivitySignals.requestProbe()
    }

    /** Coil3 全局 ImageLoader：Markdown 图片只允许 https 公网（DNS 层 + 拦截器双保险）。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = OkHttpClient.Builder()
                            .dns(MarkdownMedia.publicOnlyDns)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .addNetworkInterceptor { chain ->
                                MarkdownMedia.assertPublicHttps(chain.request().url.toString())
                                val resp = chain.proceed(chain.request())
                                MarkdownMedia.assertPublicHttps(resp.request.url.toString())
                                resp
                            }
                            .build(),
                    ),
                )
            }
            .build()
}
