package com.tormeo.app.torrent

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * HTTP stream (debrid / direct link) with a **disk ring buffer**.
 *
 * A background thread keeps downloading up to [maxAhead] bytes ahead of the player into a fixed-size file,
 * so short network hiccups never reach the player and the RAM footprint stays tiny on weak devices.
 */
class HttpSource private constructor(
    private val http: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    override val length: Long,
    override val mimeType: String,
    override val fileName: String,
    private val file: File,
    private val ring: Long,
) : ByteSource {

    private val maxAhead = ring * 3 / 4
    private val raf = RandomAccessFile(file, "rw")
    private val ch = raf.channel
    private val lock = Object()
    private val ranges = IntervalSet() // absolute file positions present in the ring, guarded by lock
    @Volatile private var readHead = 0L
    @Volatile private var closed = false
    @Volatile private var error: IOException? = null
    @Volatile var downloadRate = 0L
        private set

    private val worker = Thread(::workerLoop, "http-prefetch").apply { isDaemon = true; start() }

    override fun availableFrom(pos: Long): Long = synchronized(lock) { ranges.coveredEnd(pos) - pos }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int, cancelled: () -> Boolean): Int {
        readHead = pos
        synchronized(lock) {
            lock.notifyAll()
            while (true) {
                if (closed) throw IOException("closed")
                val end = ranges.coveredEnd(pos)
                if (end > pos) {
                    val n = minOf(len.toLong(), end - pos).toInt()
                    readRing(pos, buf, off, n)
                    return n
                }
                error?.let { throw it }
                if (cancelled()) throw IOException("cancelled")
                lock.wait(250)
            }
        }
    }

    private fun workerLoop() {
        var failures = 0
        while (!closed) {
            val target = synchronized(lock) {
                val t = ranges.coveredEnd(readHead)
                if (t >= length || t - readHead >= maxAhead) { lock.wait(300); -1L } else t
            }
            if (target < 0) continue
            try {
                download(target)
                failures = 0
                error = null
            } catch (e: IOException) {
                if (closed) break
                failures++
                if (failures >= 6) {
                    error = e
                    synchronized(lock) { lock.notifyAll() }
                }
                try { Thread.sleep(minOf(400L * failures, 4000L)) } catch (_: InterruptedException) { }
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun download(start: Long) {
        val stopAt = synchronized(lock) { ranges.nextStartAfter(start) }.coerceAtMost(length)
        val rb = Request.Builder().url(url).header("Range", "bytes=$start-${stopAt - 1}")
        headers.forEach { (k, v) -> rb.header(k, v) }
        http.newCall(rb.build()).execute().use { resp ->
            if (resp.code != 206 && !(resp.code == 200 && start == 0L)) throw IOException("HTTP ${resp.code}")
            val input = resp.body?.byteStream() ?: throw IOException("empty body")
            val buf = ByteArray(256 * 1024)
            var pos = start
            var rateBytes = 0L
            var rateT0 = System.nanoTime()
            while (!closed && pos < stopAt) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), stopAt - pos).toInt())
                if (n < 0) throw IOException("unexpected EOF")
                if (n == 0) continue
                synchronized(lock) { invalidateAliases(pos, pos + n) }
                writeRing(pos, buf, n)
                synchronized(lock) {
                    ranges.add(pos, pos + n)
                    lock.notifyAll()
                }
                pos += n
                rateBytes += n
                val now = System.nanoTime()
                if (now - rateT0 > 1_000_000_000L) {
                    downloadRate = rateBytes * 1_000_000_000L / (now - rateT0)
                    rateBytes = 0; rateT0 = now
                }
                val rh = readHead
                if (pos - rh >= maxAhead) return                                  // far enough ahead – pause
                if (rh > pos || synchronized(lock) { ranges.coveredEnd(rh) } != pos) return // player jumped elsewhere
            }
        }
    }

    /** Before writing [a,b) into the ring, forget any older data that lives in the same ring slots. */
    private fun invalidateAliases(a: Long, b: Long) {
        val toRemove = ArrayList<LongArray>()
        ranges.forEach { s, e ->
            val kMin = Math.floorDiv(s - b, ring)
            val kMax = Math.floorDiv(e - a, ring) + 1
            for (k in kMin..kMax) {
                if (k == 0L) continue
                val rs = maxOf(s, a + k * ring)
                val re = minOf(e, b + k * ring)
                if (re > rs) toRemove.add(longArrayOf(rs, re))
            }
        }
        for (r in toRemove) ranges.remove(r[0], r[1])
    }

    private fun writeRing(pos: Long, buf: ByteArray, n: Int) {
        var done = 0
        while (done < n) {
            val phys = (pos + done) % ring
            val chunk = minOf((n - done).toLong(), ring - phys).toInt()
            val bb = ByteBuffer.wrap(buf, done, chunk)
            var p = phys
            while (bb.hasRemaining()) p += ch.write(bb, p)
            done += chunk
        }
    }

    private fun readRing(pos: Long, buf: ByteArray, off: Int, n: Int) {
        var done = 0
        while (done < n) {
            val phys = (pos + done) % ring
            val chunk = minOf((n - done).toLong(), ring - phys).toInt()
            val bb = ByteBuffer.wrap(buf, off + done, chunk)
            var p = phys
            while (bb.hasRemaining()) {
                val r = ch.read(bb, p)
                if (r < 0) throw IOException("cache read past EOF")
                p += r
            }
            done += chunk
        }
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        worker.interrupt()
        runCatching { ch.close(); raf.close() }
        file.delete()
    }

    companion object {
        /**
         * Probes [url]. Returns null if the server does not support range requests
         * (then the player should open the URL directly).
         */
        fun open(
            baseClient: OkHttpClient,
            url: String,
            headers: Map<String, String>,
            cacheDir: File,
            ringBytes: Long,
            minRing: Long = 64L shl 20,
        ): HttpSource? {
            val http = baseClient.newBuilder().cache(null).readTimeout(20, TimeUnit.SECONDS).build()
            val rb = Request.Builder().url(url).header("Range", "bytes=0-0")
            headers.forEach { (k, v) -> rb.header(k, v) }
            http.newCall(rb.build()).execute().use { r ->
                if (r.code != 206) return null
                val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: return null
                val finalUrl = r.request.url.toString() // skip redirects (e.g. debrid resolvers) for later requests
                val cd = r.header("Content-Disposition")
                val name = cd?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }
                    ?: r.request.url.pathSegments.lastOrNull { it.isNotBlank() } ?: "video.mkv"
                val type = r.header("Content-Type")?.substringBefore(';')?.takeIf { it.startsWith("video/") }
                    ?: ByteSource.mimeFor(name)
                cacheDir.mkdirs()
                val free = cacheDir.usableSpace
                val ring = minOf(ringBytes, (free * 0.6).toLong(), total).coerceAtLeast(minOf(minRing, total))
                val f = File(cacheDir, "http-${System.nanoTime()}.buf")
                return HttpSource(http, finalUrl, headers, total, type, name, f, ring)
            }
        }
    }
}
