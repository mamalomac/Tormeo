package com.tormeo.app.torrent

import android.content.Context
import android.util.Log
import com.tormeo.app.data.Settings
import kotlinx.coroutines.delay
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.alert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException

data class TorrentStatus(
    val peers: Int,
    val seeds: Int,
    val downloadRate: Long,
    val hasMetadata: Boolean,
)

/**
 * libtorrent session tuned for streaming on weak devices.
 * One session for the whole app; torrents are added per playback and removed afterwards.
 */
class TorrentEngine(context: Context, private val settings: Settings) {

    private val sm = SessionManager(false)
    private val saveRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "torrents").apply { mkdirs() }
    @Volatile private var started = false

    /** Notified whenever any piece finishes, so blocked readers wake up immediately. */
    internal val pieceLock = Object()

    private val listener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(AlertType.PIECE_FINISHED.swig(), AlertType.METADATA_RECEIVED.swig())
        override fun alert(a: Alert<*>) {
            synchronized(pieceLock) { pieceLock.notifyAll() }
        }
    }

    val freeSpace: Long get() = saveRoot.usableSpace

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        val sp = SettingsPack()
            .connectionsLimit(settings.maxConnections)
            .activeDownloads(3)
            .activeSeeds(3)
            .activeLimit(6)
            .uploadRateLimit(settings.uploadLimitKb * 1024)
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), false)
        // Faster recovery from slow / stalled peers (important for time-critical pieces)
        sp.setInteger(settings_pack.int_types.request_timeout.swigValue(), 10)
        sp.setInteger(settings_pack.int_types.piece_timeout.swigValue(), 10)
        sp.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 7)
        sp.setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 1500)
        sp.setInteger(settings_pack.int_types.connection_speed.swigValue(), 30)
        sp.setInteger(settings_pack.int_types.num_want.swigValue(), 200)
        sp.setInteger(settings_pack.int_types.aio_threads.swigValue(), 4)
        sp.setInteger(settings_pack.int_types.mixed_mode_algorithm.swigValue(), 0) // prefer TCP for throughput
        sp.setInteger(settings_pack.int_types.alert_queue_size.swigValue(), 2000)
        sm.addListener(listener)
        sm.start(SessionParams(sp))
        // SessionManager enables every alert category; keep only what we use to save CPU on weak SoCs.
        val mask = alert.error_notification.or_(alert.status_notification).or_(alert.storage_notification)
            .or_(alert.piece_progress_notification)
        sm.applySettings(SettingsPack().setInteger(settings_pack.int_types.alert_mask.swigValue(), mask.to_int()))
        started = true
    }

    /**
     * Adds the torrent, waits for metadata and selects the video file.
     * [onStatus] is called roughly every 500 ms while waiting.
     */
    suspend fun open(
        infoHash: String,
        fileIdx: Int?,
        sources: List<String>?,
        filenameHint: String?,
        bitrateBps: Long?,
        onStatus: (TorrentStatus) -> Unit,
    ): TorrentSource {
        ensureStarted()
        val hash = infoHash.lowercase()
        val trackers = (sources.orEmpty().filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") } + DEFAULT_TRACKERS)
            .distinct()
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            for (t in trackers) append("&tr=").append(java.net.URLEncoder.encode(t, "UTF-8"))
        }
        val dir = File(saveRoot, hash).apply { mkdirs() }
        sm.download(magnet, dir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)

        val sha = Sha1Hash.parseHex(hash)
        var th: TorrentHandle? = null
        val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS
        while (true) {
            if (th == null || !th.isValid) th = sm.find(sha)
            val st = th?.takeIf { it.isValid }?.status()
            onStatus(TorrentStatus(st?.numPeers() ?: 0, st?.numSeeds() ?: 0, st?.downloadRate()?.toLong() ?: 0, st?.hasMetadata() == true))
            if (st != null && st.hasMetadata()) break
            if (System.currentTimeMillis() > deadline) {
                th?.let { runCatching { sm.remove(it, SessionHandle.DELETE_FILES) } }
                throw IOException("Nije pronađen nijedan peer (nema seedera?)")
            }
            delay(400)
        }
        val handle = th!!
        val ti = handle.torrentFile() ?: throw IOException("Nema metapodataka")
        val fs = ti.files()

        fun isVideo(i: Int) = fs.fileName(i).substringAfterLast('.', "").lowercase() in ByteSource.VIDEO_EXT
        val idx = when {
            fileIdx != null && fileIdx in 0 until fs.numFiles() && isVideo(fileIdx) -> fileIdx
            filenameHint != null -> (0 until fs.numFiles()).firstOrNull { fs.fileName(it).equals(filenameHint, true) }
            else -> null
        } ?: (0 until fs.numFiles()).filter { isVideo(it) }.maxByOrNull { fs.fileSize(it) }
            ?: (0 until fs.numFiles()).maxBy { fs.fileSize(it) }

        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val aheadBytes = minOf(settings.torrentAheadMb.toLong() shl 20, (freeSpace * 0.7).toLong())
            .coerceAtLeast(64L shl 20)
        return TorrentSource(this, handle, ti, idx, bitrateBps, aheadBytes)
    }

    fun status(th: TorrentHandle): TorrentStatus {
        val st = th.status()
        return TorrentStatus(st.numPeers(), st.numSeeds(), st.downloadRate().toLong(), st.hasMetadata())
    }

    fun release(th: TorrentHandle) {
        try {
            if (th.isValid) {
                if (settings.keepCache) sm.remove(th) else sm.remove(th, SessionHandle.DELETE_FILES)
            }
        } catch (e: Exception) {
            Log.w("TorrentEngine", "remove failed", e)
        }
    }

    /** Deletes everything in the torrent cache (only call when nothing is playing). */
    fun clearCache() {
        saveRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun cacheSize(): Long = saveRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val METADATA_TIMEOUT_MS = 60_000L
        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
        )
    }
}
