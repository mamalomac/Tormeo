package com.tormeo.app.torrent

/** Sorted, non-overlapping set of half-open byte ranges [start, end). Not thread-safe. */
class IntervalSet {
    private val starts = ArrayList<Long>()
    private val ends = ArrayList<Long>()

    val size: Int get() = starts.size
    fun clear() { starts.clear(); ends.clear() }

    fun add(start: Long, end: Long) {
        if (end <= start) return
        var s = start
        var e = end
        var i = 0
        while (i < starts.size && ends[i] < s) i++
        while (i < starts.size && starts[i] <= e) {
            s = minOf(s, starts[i]); e = maxOf(e, ends[i])
            starts.removeAt(i); ends.removeAt(i)
        }
        starts.add(i, s); ends.add(i, e)
    }

    fun remove(start: Long, end: Long) {
        if (end <= start) return
        var i = 0
        while (i < starts.size) {
            val s = starts[i]; val e = ends[i]
            if (e <= start || s >= end) { i++; continue }
            starts.removeAt(i); ends.removeAt(i)
            if (s < start) { starts.add(i, s); ends.add(i, start); i++ }
            if (e > end) { starts.add(i, end); ends.add(i, e); i++ }
        }
    }

    /** End of the range containing [pos], or [pos] itself if not covered. */
    fun coveredEnd(pos: Long): Long {
        for (i in starts.indices) {
            if (starts[i] > pos) break
            if (pos < ends[i]) return ends[i]
        }
        return pos
    }

    /** Start of the first range beginning after [pos], or Long.MAX_VALUE. */
    fun nextStartAfter(pos: Long): Long {
        for (s in starts) if (s > pos) return s
        return Long.MAX_VALUE
    }

    fun forEach(block: (Long, Long) -> Unit) {
        for (i in starts.indices) block(starts[i], ends[i])
    }

    fun snapshot(): List<LongRange> = starts.indices.map { starts[it] until ends[it] }
}
