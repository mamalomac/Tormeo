package com.tormeo.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Test

class IntervalSetTest {
    @Test fun addMergesAndRemoveSplits() {
        val s = IntervalSet()
        s.add(0, 10); s.add(20, 30); s.add(10, 20)
        assertEquals(listOf(0L until 30L), s.snapshot())
        s.remove(5, 8)
        assertEquals(listOf(0L until 5L, 8L until 30L), s.snapshot())
        assertEquals(5L, s.coveredEnd(0))
        assertEquals(30L, s.coveredEnd(8))
        assertEquals(6L, s.coveredEnd(6)) // not covered
        assertEquals(8L, s.nextStartAfter(5))
        s.add(40, 50); s.add(35, 45)
        assertEquals(listOf(0L until 5L, 8L until 30L, 35L until 50L), s.snapshot())
        s.remove(0, 100)
        assertEquals(0, s.size)
    }
}
