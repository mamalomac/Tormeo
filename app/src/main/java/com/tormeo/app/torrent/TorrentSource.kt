package com.tormeo.app.torrent

import kotlinx.coroutines.delay
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

/**
 * One video file inside a torrent, readable at any offset.
 *
 * Streaming strategy:
 *  - Only pieces in a window [playhead, playhead + aheadBytes] are wanted, downloaded sequentially
 *    (disk usage stays bounded, bandwidth goes where the player needs it).
 *  - The next ~20 s of video get piece *deadlines*, so libtorrent requests them from the fastest peers
 *    and duplicates slow requests (time-critical mode).
 *  - Before playback starts we fetch the head of the file plus its tail (MKV cues / MP4 moov),
 *    so the player can seek immediately and never stalls during the first minutes.
 */
class TorrentSource internal constructor(
    private val engine: TorrentEngine,
    val handle: TorrentHandle,
    ti: TorrentInfo,
    val fileIndex: Int,
    bitrateBps: Long?,
    private val aheadBytes: Long,
) : ByteSource {

    private val fs = ti.files()
    private val fileOffset = fs.fileOffset(fileIndex)
    override val length: Long = fs.fileSize(fileIndex)
    override val fileName: String = fs.fileName(fileIndex)
    override val mimeType: String = ByteSource.mimeFor(fileName)
    private val pieceLen = ti.pieceLength().toLong()
    private val numPieces = ti.numPieces()
    private val firstPiece = (fileOffset / pieceLen).toInt()
    private val lastPiece = ((fileOffset + length - 1) / pieceLen).toInt()
    private val path = File(fs.filePath(fileIndex, handle.savePath()))

    /** Bytes per second of video; estimated if unknown (assume a 2 h runtime). */
    private val bytesPerSec: Long = ((bitrateBps ?: (length * 8 / 7200)) / 8).coerceIn(250_000, 20_000_000)
    private val criticalPieces: Int = ((bytesPerSec * 20 / pieceLen).toInt() + 2).coerceIn(3, 40)
    private val windowPieces: Int = (aheadBytes / pieceLen).toInt().coerceIn(criticalPieces + 2, numPieces)

    @Volatile private var closed = false
    @Volatile private var headPiece = -1
    private var windowStart = -1
    private var raf: RandomAccessFile? = null
    private val rafLock = Object()

    init {
        // Everything outside our file is ignored; inside it, only the window around the playhead is wanted.
        updateWindow(firstPiece, force = true)
    }

    private fun pieceAt(pos: Long): Int = ((fileOffset + pos) / pieceLen).toInt().coerceIn(firstPiece, lastPiece)

    /** Moves the download window / deadlines when the playhead enters a new piece. */
    @Synchronized
    private fun updateWindow(piece: Int, force: Boolean = false) {
        if (!force && piece == headPiece) return
        val jumped = force || windowStart < 0 || piece < headPiece || piece > headPiece + criticalPieces
        headPiece = piece
        if (!handle.isValid) return

        // Re-prioritize the whole file only on seeks or when the window moved by > 1/4 (it's an O(numPieces) call).
        if (jumped || piece - windowStart > windowPieces / 4) {
            windowStart = piece
            val prio = Array(numPieces) { Priority.IGNORE }
            val end = minOf(lastPiece, piece + windowPieces)
            for (i in piece..end) prio[i] = Priority.DEFAULT
            // always keep the tail (container index) wanted
            for (i in maxOf(firstPiece, lastPiece - 1)..lastPiece) prio[i] = Priority.TOP_PRIORITY
            handle.prioritizePieces(prio)
        }
        if (jumped) handle.clearPieceDeadlines()
        val stepMs = (pieceLen * 1000 / bytesPerSec / 2).coerceIn(30, 2000).toInt()
        for (i in 0 until criticalPieces) {
            val p = piece + i
            if (p > lastPiece) break
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, i * stepMs)
        }
    }

    private fun waitPiece(p: Int, cancelled: () -> Boolean) {
        if (handle.havePiece(p)) return
        handle.setPieceDeadline(p, 0)
        while (true) {
            if (closed) throw IOException("closed")
            if (cancelled()) throw IOException("cancelled")
            synchronized(engine.pieceLock) { engine.pieceLock.wait(200) }
            if (!handle.isValid) throw IOException("torrent removed")
            if (handle.havePiece(p)) return
        }
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int, cancelled: () -> Boolean): Int {
        val p = pieceAt(pos)
        updateWindow(p)
        waitPiece(p, cancelled)
        val pieceEndInFile = (p + 1L) * pieceLen - fileOffset
        val n = minOf(len.toLong(), minOf(pieceEndInFile, length) - pos).toInt()
        synchronized(rafLock) {
            val f = raf ?: openFile(cancelled).also { raf = it }
            f.seek(pos)
            f.readFully(buf, off, n)
        }
        return n
    }

    /** The file appears on disk shortly after its first piece is written – wait a little if needed. */
    private fun openFile(cancelled: () -> Boolean): RandomAccessFile {
        var waited = 0
        while (true) {
            if (path.exists()) return RandomAccessFile(path, "r")
            if (closed || cancelled() || waited >= 10_000) throw FileNotFoundException("Torrent fajl nije na disku: $path")
            Thread.sleep(100)
            waited += 100
        }
    }

    override fun availableFrom(pos: Long): Long {
        var p = pieceAt(pos)
        if (!handle.isValid || !handle.havePiece(p)) return 0
        while (p < lastPiece && handle.havePiece(p + 1)) p++
        return minOf(length, (p + 1L) * pieceLen - fileOffset) - pos
    }

    /** Head of the file + its last pieces must be present before starting. */
    override suspend fun prebuffer(bytes: Long, onProgress: (Float) -> Unit) {
        val headLast = pieceAt(minOf(length - 1, maxOf(bytes, pieceLen)))
        val needed = (firstPiece..headLast) + (maxOf(firstPiece, lastPiece - 1)..lastPiece)
        val wanted = needed.distinct()
        wanted.forEachIndexed { i, p -> if (!handle.havePiece(p)) handle.setPieceDeadline(p, i * 100) }
        while (true) {
            if (closed || !handle.isValid) throw IOException("closed")
            val have = wanted.count { handle.havePiece(it) }
            onProgress(have.toFloat() / wanted.size)
            if (have == wanted.size) break
            delay(300)
        }
        updateWindow(firstPiece, force = true)
    }

    fun status(): TorrentStatus = engine.status(handle)

    override fun close() {
        closed = true
        synchronized(engine.pieceLock) { engine.pieceLock.notifyAll() }
        synchronized(rafLock) { runCatching { raf?.close() }; raf = null }
        engine.release(handle)
    }
}
