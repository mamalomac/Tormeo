package com.tormeo.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tormeo.app.TormeoApp
import com.tormeo.app.data.PlayRequest
import com.tormeo.app.data.StremioJson
import com.tormeo.app.torrent.ByteSource
import com.tormeo.app.torrent.HttpSource
import com.tormeo.app.torrent.TorrentSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private val app get() = TormeoApp.instance
    private lateinit var req: PlayRequest

    private lateinit var playerView: PlayerView
    private lateinit var overlay: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var bufferText: TextView

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    @Volatile private var source: ByteSource? = null
    private var serverId: String? = null
    private var prepareJob: Job? = null
    private var tickerJob: Job? = null
    private var triedWithoutTunneling = false
    private var displayModeMatched = false
    private var errorShown = false
    private var resumeFrom = 0L
    private var ioRetries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        req = StremioJson.decodeFromString(PlayRequest.serializer(), intent.getStringExtra(EXTRA_REQUEST)!!)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        buildUi()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, playerView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (player != null && playerView.isControllerFullyVisible) playerView.hideController() else finish()
            }
        })

        if (intent.getBooleanExtra(EXTRA_RESUME, true)) {
            app.progress.get(req.videoId)?.takeIf { it.fraction in 0.01f..0.94f }?.let { resumeFrom = it.positionMs }
        }
        startPrepare()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = true
            controllerShowTimeoutMs = 4000
            controllerAutoShow = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowSubtitleButton(true)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(playerView)

        titleText = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
            text = listOfNotNull(req.title, req.subtitle).joinToString(" · ")
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(360), dp(10)).apply { topMargin = dp(20) }
        }
        statusText = TextView(this).apply {
            setTextColor(0xFFB8BAC7.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
            text = "Pripremam…"
        }
        val hint = TextView(this).apply {
            setTextColor(0xFF6E7080.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) }
            text = "NAZAD za odustajanje"
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xE60E0F13.toInt())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(titleText); addView(progressBar); addView(statusText); addView(hint)
        }
        root.addView(overlay)

        bufferText = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) }
            visibility = View.GONE
        }
        root.addView(bufferText)
        setContentView(root)
    }

    private fun setStatus(text: String, progress: Float? = null) = runOnUiThread {
        statusText.text = text
        if (progress == null) progressBar.isIndeterminate = true
        else { progressBar.isIndeterminate = false; progressBar.progress = (progress * 1000).toInt() }
    }

    private fun showError(msg: String) = runOnUiThread {
        errorShown = true
        overlay.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        statusText.text = "Greška: $msg\n\nOK = pokušaj ponovo · NAZAD = izlaz"
    }

    // ------------------------------------------------------------------ source preparation

    private val bytesPerSec: Long
        get() = ((req.bitrateBps ?: req.sizeBytes?.let { it * 8 / 7200 } ?: 25_000_000L) / 8)

    private fun startPrepare() {
        errorShown = false
        progressBar.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        prepareJob = lifecycleScope.launch {
            try {
                val src = withContext(Dispatchers.IO) { openSource() }
                val url: String
                var headers: Map<String, String>? = null
                if (src != null) {
                    val (id, u) = app.streamServer.register(src)
                    serverId = id
                    url = u
                } else {
                    url = req.url ?: error("Nema linka")
                    headers = req.headers
                }
                setStatus("Pokrećem plejer…")
                startPlayer(url, headers, local = src != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun openSource(): ByteSource? {
        val prebufferSec = app.settings.prebufferSeconds
        val hash = req.infoHash
        if (hash != null) {
            setStatus("Povezivanje sa peer-ovima…")
            val ts = app.torrentEngine.open(hash, req.fileIdx, req.sources, req.filename, req.bitrateBps) { st ->
                setStatus(if (st.hasMetadata) "Metapodaci primljeni" else "Tražim peer-ove… ${st.peers} povezano · ${speed(st.downloadRate)}")
            }
            source = ts
            val bytes = (bytesPerSec * prebufferSec).coerceIn(8L shl 20, 400L shl 20)
            ts.prebuffer(bytes) { f ->
                val st = ts.status()
                setStatus("Baferovanje ${(f * 100).toInt()}% · ${st.peers} peer-ova · ${speed(st.downloadRate)}", f)
            }
            return ts
        }
        val url = req.url ?: error("Stream nema link")
        if (url.contains(".m3u8") || url.startsWith("rtmp") || url.startsWith("rtsp")) return null // adaptive/live – play directly
        setStatus("Povezivanje…")
        val cacheDir = File(externalCacheDir ?: cacheDir, "http")
        cacheDir.listFiles()?.forEach { it.delete() } // leftovers from a crash
        val hs = HttpSource.open(app.http, url, req.headers.orEmpty(), cacheDir, app.settings.httpBufferMb.toLong() shl 20)
            ?: return null
        source = hs
        val bytes = (bytesPerSec * prebufferSec / 2).coerceIn(4L shl 20, 200L shl 20)
        hs.prebuffer(bytes) { f -> setStatus("Baferovanje ${(f * 100).toInt()}% · ${speed(hs.downloadRate)}", f) }
        return hs
    }

    // ------------------------------------------------------------------ player

    private fun startPlayer(url: String, headers: Map<String, String>?, local: Boolean) {
        val settings = app.settings
        val caps = app.deviceCaps
        val dsf = DefaultHttpDataSource.Factory()
            .setUserAgent("Tormeo/1.0")
            .setConnectTimeoutMs(15_000)
            // reads from the local server block while pieces download – don't time out
            .setReadTimeoutMs(if (local) 180_000 else 30_000)
            .setAllowCrossProtocolRedirects(true)
        if (!headers.isNullOrEmpty()) dsf.setDefaultRequestProperties(headers)
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

        val renderers = DefaultRenderersFactory(this)
            // Hardware MediaCodec first; ffmpeg only kicks in for audio formats the device can't decode
            .setExtensionRendererMode(
                if (settings.ffmpegAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            )
            .setEnableDecoderFallback(true)

        val ts = DefaultTrackSelector(this)
        ts.parameters = ts.buildUponParameters()
            .setTunnelingEnabled(settings.tunneling && !triedWithoutTunneling)
            .setExceedRendererCapabilitiesIfNecessary(true)
            .apply {
                if (settings.audioLang.isNotEmpty()) setPreferredAudioLanguage(settings.audioLang)
                if (settings.subLang.isNotEmpty()) setPreferredTextLanguage(settings.subLang)
            }
            .build()
        trackSelector = ts

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
            .setTargetBufferBytes(caps.playerBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(ts)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsf, extractors))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        if (settings.matchFrameRate) p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        p.addListener(listener)
        player = p
        playerView.player = p
        p.setMediaItem(MediaItem.fromUri(url), resumeFrom)
        p.prepare()
        p.playWhenReady = true
        if (resumeFrom > 0) Toast.makeText(this, "Nastavljam od ${fmtTime(resumeFrom)}", Toast.LENGTH_SHORT).show()
        startTicker()
        playerView.requestFocus()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    ioRetries = 0
                    overlay.visibility = View.GONE
                    bufferText.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    app.progress.remove(req.videoId)
                    finish()
                }
                else -> Unit
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            player?.videoFormat?.let { matchDisplayMode(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val decoderProblem = error.errorCode in listOf(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )
            // Some TV firmwares advertise tunneling but break with it – retry once without.
            if (decoderProblem && app.settings.tunneling && !triedWithoutTunneling) {
                triedWithoutTunneling = true
                trackSelector?.let { it.parameters = it.buildUponParameters().setTunnelingEnabled(false).build() }
                p.prepare()
                p.playWhenReady = true
                return
            }
            if (error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE &&
                ioRetries++ < 5
            ) {
                // network hiccup – just continue from where we are
                p.prepare()
                return
            }
            showError("${error.errorCodeName}: ${error.message}")
        }
    }

    /** Switch display refresh rate (and to a 4K mode for 4K content) to match the video. */
    private fun matchDisplayMode(format: Format) {
        if (displayModeMatched || !app.settings.matchFrameRate) return
        val fps = format.frameRate
        if (fps <= 0f || format.width <= 0) return
        displayModeMatched = true
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val modes = display.supportedModes
        val cur = display.mode
        val wantUhd = format.width >= 3000 || format.height >= 1600
        val sameRes = modes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        val pool = if (wantUhd) modes.filter { it.physicalWidth >= 3800 }.ifEmpty { sameRes } else sameRes
        fun score(refresh: Float): Float? {
            for (k in 1..4) {
                val d = abs(refresh - fps * k)
                if (d < 0.06f * k) return k + d
            }
            return null
        }
        val best = pool.mapNotNull { m -> score(m.refreshRate)?.let { m to it } }.minByOrNull { it.second }?.first ?: return
        if (best.modeId != cur.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            var n = 0
            while (isActive) {
                delay(1000)
                val p = player ?: continue
                if (p.playbackState == Player.STATE_BUFFERING && overlay.visibility != View.VISIBLE) {
                    val src = source
                    val info = withContext(Dispatchers.IO) {
                        when (src) {
                            is TorrentSource -> src.status().let { "${it.peers} peer-ova · ${speed(it.downloadRate)}" }
                            is HttpSource -> speed(src.downloadRate)
                            else -> ""
                        }
                    }
                    bufferText.text = "Baferovanje… $info"
                    bufferText.visibility = View.VISIBLE
                } else {
                    bufferText.visibility = View.GONE
                }
                if (++n % 15 == 0) saveProgress()
            }
        }
    }

    private fun saveProgress() {
        val p = player ?: return
        val dur = p.duration
        if (dur > 0 && p.currentPosition > 5_000) app.progress.save(req, p.currentPosition, dur)
    }

    // ------------------------------------------------------------------ keys & lifecycle

    @SuppressLint("RestrictedApi") // false positive: androidx.core ComponentActivity marks this override as restricted
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (errorShown && event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            retry()
            return true
        }
        if (player != null && event.keyCode != KeyEvent.KEYCODE_BACK) {
            // OK / play-pause toggles playback when the controller is hidden (typical TV behaviour)
            if (!playerView.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE)
            ) {
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }
            if (playerView.dispatchKeyEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun retry() {
        errorShown = false
        val p = player
        if (p != null) {
            overlay.visibility = View.GONE
            p.prepare()
            p.playWhenReady = true
        } else {
            releaseAll()
            startPrepare()
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        // Free decoder, network and torrent resources as soon as we leave – weak devices need them back.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
        if (Build.VERSION.SDK_INT >= 23) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }

    private fun releaseAll() {
        prepareJob?.cancel()
        tickerJob?.cancel()
        player?.let { it.removeListener(listener); it.release() }
        player = null
        serverId?.let { app.streamServer.unregister(it) }
        serverId = null
        val src = source
        source = null
        if (src != null) Thread { runCatching { src.close() } }.start()
    }

    companion object {
        private const val EXTRA_REQUEST = "req"
        private const val EXTRA_RESUME = "resume"

        fun start(context: Context, request: PlayRequest, resume: Boolean = true) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_REQUEST, StremioJson.encodeToString(PlayRequest.serializer(), request))
                    .putExtra(EXTRA_RESUME, resume),
            )
        }

        fun speed(bps: Long): String = when {
            bps >= 1_000_000 -> String.format("%.1f MB/s", bps / 1_000_000.0)
            else -> "${bps / 1000} KB/s"
        }

        fun fmtTime(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
        }
    }
}
