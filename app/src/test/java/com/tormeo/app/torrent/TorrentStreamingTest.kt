package com.tormeo.app.torrent

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import kotlin.random.Random

/**
 * Real libtorrent end to end on loopback: a seeder session shares a multi-file torrent, the app's
 * [TorrentEngine] fetches it via magnet (metadata from the peer), and the video file is read through
 * [StreamServer] exactly like ExoPlayer does – sequentially and with seeks – and compared byte for byte.
 */
class TorrentStreamingTest {
    private lateinit var root: File
    private var seeder: SessionManager? = null

    @Before fun loadNative() {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch")
        assumeTrue("needs linux x86_64", os.contains("linux") && (arch == "amd64" || arch == "x86_64"))
        root = Files.createTempDirectory("tormeo-torrent").toFile()
        if (System.getProperty("libtorrent4j.jni.path").isNullOrEmpty()) {
            val so = File(root, "libtorrent4j.so")
            javaClass.classLoader!!.getResourceAsStream("lib/x86_64/libtorrent4j.so")!!.use { inp ->
                so.outputStream().use { inp.copyTo(it) }
            }
            System.setProperty("libtorrent4j.jni.path", so.absolutePath)
        }
    }

    @After fun cleanup() {
        seeder?.stop()
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test(timeout = 180_000)
    fun streamsVideoFileFromMultiFileTorrent(): Unit = runBlocking {
        // --- content: a small extra file first, then the "movie" (so the video is not file index 0)
        val seedDir = File(root, "seed").apply { mkdirs() }
        val content = File(seedDir, "Movie").apply { mkdirs() }
        File(content, "a-readme.txt").writeText("hello\n".repeat(5000))
        val movie = Random(1).nextBytes(9 * 1024 * 1024 + 4321)
        File(content, "movie.mkv").writeBytes(movie)
        File(content, "z-sample.mkv").writeBytes(Random(2).nextBytes(300_000))

        val result = TorrentBuilder().path(content).pieceSize(64 * 1024).generate()
        val ti = TorrentInfo(result.entry().bencode())
        val hash = ti.infoHash().toHex()
        val fs = ti.files()
        val movieIdx = (0 until fs.numFiles()).first { fs.fileName(it) == "movie.mkv" }

        // --- seeder
        val seedPort = freePort()
        val sm = SessionManager(false).also { seeder = it }
        val sp = SettingsPack().listenInterfaces("127.0.0.1:$seedPort")
        sp.setEnableDht(false); sp.setEnableLsd(false)
        sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
        sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        sm.start(SessionParams(sp))
        sm.download(ti, seedDir)
        var waited = 0
        while (sm.find(ti.infoHash())?.status()?.isSeeding != true) {
            Thread.sleep(100); waited += 100
            check(waited < 30_000) { "seeder did not finish checking" }
        }

        // --- app engine (leecher)
        val engine = TorrentEngine(File(root, "leech")) { TorrentConfig(listenInterfaces = "127.0.0.1:${freePort()}") }
        val src = engine.open(hash, movieIdx, null, null, null, extraPeers = listOf("127.0.0.1:$seedPort")) { }
        assertEquals(movie.size.toLong(), src.length)
        assertEquals("movie.mkv", src.fileName)
        var lastProgress = 0f
        src.prebuffer(2L shl 20) { lastProgress = it }
        assertEquals(1f, lastProgress)

        val server = StreamServer().also { it.start() }
        val (_, url) = server.register(src)

        fun fetch(from: Long, len: Int): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.readTimeout = 60_000
            c.setRequestProperty("Range", "bytes=$from-${from + len - 1}")
            assertEquals(206, c.responseCode)
            val out = ByteArray(len)
            DataInputStream(c.inputStream).use { it.readFully(out) }
            c.disconnect()
            return out
        }

        // seek to the end first (MKV cues), then play from start, then random seeks
        val tail = movie.size - 50_000L
        assertArrayEquals(movie.copyOfRange(tail.toInt(), movie.size), fetch(tail, 50_000))
        var pos = 0L
        while (pos < movie.size) {
            val n = minOf(1_000_003L, movie.size - pos).toInt()
            assertArrayEquals("at $pos", movie.copyOfRange(pos.toInt(), pos.toInt() + n), fetch(pos, n))
            pos += n
        }
        val rnd = Random(3)
        repeat(10) {
            val p = rnd.nextLong(movie.size - 100_000L)
            assertArrayEquals("seek $p", movie.copyOfRange(p.toInt(), p.toInt() + 100_000), fetch(p, 100_000))
        }
        src.close()
    }
}
