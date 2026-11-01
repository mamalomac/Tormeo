package com.tormeo.app.torrent

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Tiny HTTP/1.1 server on 127.0.0.1 with Range support.
 * ExoPlayer reads torrents and disk-buffered HTTP streams through it as if they were normal files.
 */
class StreamServer {
    private class Entry(val source: ByteSource) {
        val latestRequest = AtomicLong(0)
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "stream-http").apply { isDaemon = true } }
    private lateinit var server: ServerSocket
    val port: Int get() = server.localPort

    fun start() {
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        Thread({
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                pool.execute { handle(s) }
            }
        }, "stream-accept").apply { isDaemon = true }.start()
    }

    fun register(source: ByteSource): Pair<String, String> {
        val id = UUID.randomUUID().toString().substring(0, 8)
        entries[id] = Entry(source)
        val name = URLEncoder.encode(source.fileName, "UTF-8").replace("+", "%20")
        return id to "http://127.0.0.1:$port/s/$id/$name"
    }

    fun unregister(id: String) {
        entries.remove(id)
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                s.sendBufferSize = 512 * 1024
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(input) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: return
                val out = BufferedOutputStream(s.getOutputStream(), 256 * 1024)

                val id = path.removePrefix("/s/").substringBefore('/')
                val entry = entries[id]
                if (entry == null || !path.startsWith("/s/")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val src = entry.source
                val total = src.length
                var start = 0L
                var end = total - 1
                val range = headers["range"]
                val partial = range != null && range.startsWith("bytes=")
                if (partial) {
                    val spec = range!!.removePrefix("bytes=").substringBefore(',')
                    val a = spec.substringBefore('-').trim()
                    val b = spec.substringAfter('-', "").trim()
                    if (a.isEmpty()) { // suffix range: last N bytes
                        start = (total - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
                    } else {
                        start = a.toLongOrNull() ?: 0
                        if (b.isNotEmpty()) end = minOf(b.toLongOrNull() ?: end, total - 1)
                    }
                }
                if (start >= total) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val len = end - start + 1
                val sb = StringBuilder()
                sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                sb.append("Content-Type: ").append(src.mimeType).append("\r\n")
                sb.append("Accept-Ranges: bytes\r\n")
                sb.append("Content-Length: ").append(len).append("\r\n")
                if (partial) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray())
                // Send headers right away: the player's "open" succeeds even while pieces are still downloading,
                // and any later problem surfaces as a read error (which the player retries) instead of a dead connection.
                out.flush()
                if (method == "HEAD") return

                // Newest request for a source wins: a reader stuck waiting on an old position gives up.
                val reqNo = entry.latestRequest.incrementAndGet()
                val cancelled = { entry.latestRequest.get() != reqNo || entries[id] == null }
                val buf = ByteArray(128 * 1024)
                var pos = start
                var remaining = len
                while (remaining > 0) {
                    val n = src.read(pos, buf, 0, minOf(buf.size.toLong(), remaining).toInt(), cancelled)
                    out.write(buf, 0, n)
                    pos += n
                    remaining -= n
                    // flush small first chunk quickly so the player can start parsing
                    if (pos - start <= buf.size) out.flush()
                }
                out.flush()
            } catch (e: IOException) {
                // usually the client closed the connection (seek / stop) – log anything else
                val m = e.message.orEmpty()
                if ("cancelled" !in m && "closed" !in m && "Broken pipe" !in m && "reset" !in m) Log.w(TAG, "stream read failed", e)
            } catch (e: Exception) {
                Log.w(TAG, "stream error", e)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    companion object { private const val TAG = "StreamServer" }
}
