package com.tormeo.app.torrent

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.random.Random

/**
 * End-to-end: fake "debrid" HTTP server → HttpSource (tiny ring buffer, forces wrap-around + aliasing)
 * → StreamServer → HttpURLConnection range reads, compared byte-for-byte with the original.
 */
class HttpSourceTest {
    private val size = 24 * 1024 * 1024 + 12345
    private val data = Random(42).nextBytes(size)
    private var throttle = false

    /** Minimal range-capable origin ("debrid CDN") built on a raw ServerSocket. */
    private lateinit var origin: ServerSocket
    private val originPort get() = origin.localPort

    @Before fun setUp() {
        origin = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!origin.isClosed) {
                val s = try { origin.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) = s.use {
        try {
            val r = BufferedReader(InputStreamReader(s.getInputStream()))
            r.readLine() ?: return@use
            var range: String? = null
            while (true) {
                val l = r.readLine() ?: break
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("range:")) range = l.substringAfter(':').trim()
            }
            val out = s.getOutputStream()
            var start = 0; var end = size - 1
            if (range != null) {
                val spec = range.removePrefix("bytes=")
                start = spec.substringBefore('-').toInt()
                spec.substringAfter('-').takeIf { it.isNotEmpty() }?.let { end = minOf(it.toInt(), size - 1) }
                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/x-matroska\r\n" +
                    "Content-Range: bytes $start-$end/$size\r\nContent-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n").toByteArray())
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
            }
            var p = start
            while (p <= end) {
                val n = minOf(64 * 1024, end - p + 1)
                out.write(data, p, n)
                p += n
                if (throttle) Thread.sleep(1)
            }
            out.flush()
        } catch (_: Exception) { }
    }

    @After fun tearDown() { origin.close() }

    private fun fetch(url: String, from: Long, len: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=$from-${from + len - 1}")
        assertEquals(206, c.responseCode)
        val out = ByteArray(len)
        DataInputStream(c.inputStream).use { it.readFully(out) }
        c.disconnect()
        return out
    }

    @Test fun ringBufferServesCorrectBytesWithSeeks() {
        val dir = Files.createTempDirectory("tormeo").toFile()
        val ring = 3L * 1024 * 1024 + 777 // deliberately odd and much smaller than the file
        val src = HttpSource.open(OkHttpClient(), "http://127.0.0.1:$originPort/movie.mkv", emptyMap(), dir, ring, minRing = 1)
        assertNotNull(src)
        src!!
        assertEquals(size.toLong(), src.length)
        assertEquals("video/x-matroska", src.mimeType)

        val server = StreamServer().also { it.start() }
        val (_, url) = server.register(src)

        // sequential read of the whole file in odd-sized chunks (wraps the ring many times)
        var pos = 0L
        val chunk = 700_001
        while (pos < size) {
            val n = minOf(chunk.toLong(), size - pos).toInt()
            assertArrayEquals("at $pos", data.copyOfRange(pos.toInt(), pos.toInt() + n), fetch(url, pos, n))
            pos += n
        }
        // random seeks: tail (like MKV cues), back to start, middle
        val rnd = Random(7)
        val points = listOf(size - 100_000L, 0L, 5_000_000L) + List(25) { rnd.nextLong(size - 300_000L) }
        for (p in points) {
            val n = minOf(250_000, (size - p).toInt())
            assertArrayEquals("seek $p", data.copyOfRange(p.toInt(), p.toInt() + n), fetch(url, p, n))
        }
        // full-file request without Range header
        val c = URL(url).openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals(size.toLong(), c.contentLengthLong)
        c.disconnect()

        src.close()
        dir.deleteRecursively()
    }

    @Test fun prebufferWaitsForData(): Unit = kotlinx.coroutines.runBlocking {
        throttle = true
        val dir = Files.createTempDirectory("tormeo").toFile()
        val src = HttpSource.open(OkHttpClient(), "http://127.0.0.1:$originPort/movie.mkv", emptyMap(), dir, 8L shl 20, minRing = 1)!!
        var last = 0f
        src.prebuffer(2L shl 20) { last = it }
        assertEquals(1f, last)
        assert(src.availableFrom(0) >= 2L shl 20)
        src.close()
        dir.deleteRecursively()
    }
}
