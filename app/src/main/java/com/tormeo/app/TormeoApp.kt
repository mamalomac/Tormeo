package com.tormeo.app

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.tormeo.app.data.AddonRepository
import com.tormeo.app.data.Settings
import com.tormeo.app.data.WatchProgressStore
import com.tormeo.app.device.DeviceCaps
import com.tormeo.app.diag.Diagnostics
import com.tormeo.app.torrent.StreamServer
import com.tormeo.app.torrent.TorrentConfig
import com.tormeo.app.torrent.TorrentEngine
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class TormeoApp : Application() {

    lateinit var http: OkHttpClient
        private set
    lateinit var settings: Settings
        private set
    lateinit var addons: AddonRepository
        private set
    lateinit var progress: WatchProgressStore
        private set

    /** True on Android TV / TV boxes (or the dedicated TV build). Drives defaults and layout. */
    val isTv: Boolean by lazy {
        if (BuildConfig.IS_TV_BUILD) return@lazy true
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    val deviceCaps: DeviceCaps by lazy { DeviceCaps.detect(this) }
    val torrentEngine: TorrentEngine by lazy {
        TorrentEngine(File(getExternalFilesDir(null) ?: filesDir, "torrents")) {
            TorrentConfig(settings.maxConnections, settings.uploadLimitKb, settings.torrentAheadMb, settings.keepCache)
        }
    }
    val streamServer: StreamServer by lazy { StreamServer().also { it.start() } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Diagnostics.init(this)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .cache(Cache(File(cacheDir, "http"), 30L * 1024 * 1024))
            .build()
        settings = Settings(this, isTv)
        addons = AddonRepository(this, http)
        progress = WatchProgressStore(this)

        // Image loading tuned for low-RAM devices: small memory cache, RGB_565, no crossfade.
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, if (deviceCaps.lowRam) 0.10 else 0.18).build() }
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
                .allowRgb565(true)
                .crossfade(false)
                .build()
        }
    }

    companion object {
        lateinit var instance: TormeoApp
            private set
    }
}
