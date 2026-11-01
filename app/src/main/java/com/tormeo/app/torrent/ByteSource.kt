package com.tormeo.app.torrent

import java.io.Closeable

/** A seekable, blocking byte source served to the player by [StreamServer]. */
interface ByteSource : Closeable {
    val length: Long
    val mimeType: String
    val fileName: String

    /**
     * Reads up to [len] bytes at [pos], blocking until data is available.
     * Returns the number of bytes read (> 0). Throws IOException when closed or [cancelled].
     */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int, cancelled: () -> Boolean): Int

    /** Bytes available contiguously from [pos] without waiting (for the buffering indicator). */
    fun availableFrom(pos: Long): Long

    /** Downloads enough data for a stutter-free start. Reports progress 0..1. */
    suspend fun prebuffer(bytes: Long, onProgress: (Float) -> Unit) {
        val target = minOf(bytes, length)
        while (true) {
            val have = availableFrom(0)
            onProgress((have.toFloat() / target).coerceIn(0f, 1f))
            if (have >= target) return
            kotlinx.coroutines.delay(250)
        }
    }

    companion object {
        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }

        val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "webm", "ts", "m2ts", "mov", "wmv", "mpg", "mpeg")
    }
}
