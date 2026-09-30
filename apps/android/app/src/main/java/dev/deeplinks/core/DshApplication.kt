package dev.deeplinks.core

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
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
        // K0：最先装崩溃记录，越早越好（后面的初始化万一崩了也要留证据）。
        CrashRecorder.install(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        LocaleManager.init(this)
        ThemeManager.init(this)
        FontScaleManager.init(this)
        watchNetwork()
    }

    /**
     * 网络变化（连上 / 断开 Wi-Fi、换蜂窝、地址变了）让自动选路重新探测局域网（RFC §7.2 第 1 条）。
     * 首次注册会立刻回调一次 onAvailable，顺带作废进程启动前的一切旧结论，无副作用。
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()
                override fun onLost(network: Network) = onNetworkChanged()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    onNetworkChanged()
            })
        }
    }

    /** 网络变化：作废选路缓存，并请首页探测循环立刻复核（R4；requestProbe 自带 2 秒去抖）。 */
    private fun onNetworkChanged() {
        HostHttp.onNetworkChanged()
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
