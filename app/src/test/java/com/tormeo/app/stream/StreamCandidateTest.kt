package com.tormeo.app.stream

import com.tormeo.app.data.InstalledAddon
import com.tormeo.app.data.Manifest
import com.tormeo.app.data.Stream
import com.tormeo.app.data.StreamResponse
import com.tormeo.app.data.StremioJson
import com.tormeo.app.device.DeviceCaps
import com.tormeo.app.device.DeviceCaps.Codec as HwCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCandidateTest {
    private val addon = InstalledAddon("https://torrentio.strem.fun/manifest.json", Manifest(id = "torrentio", name = "Torrentio"))

    /** Typical cheap 4K Android TV box: HW HEVC 4K 10-bit, no AV1, no Dolby Vision, HDR10 TV. */
    private val weakBox = DeviceCaps(
        hevc = HwCodec(true, true, true), avc = HwCodec(true, false, false), av1 = HwCodec(), vp9 = HwCodec(true, true, true),
        dolbyVision = false, displayHdr10 = true, displayHlg = true, displayDv = false, display4k = true,
        lowRam = false, memoryClassMb = 256, totalRamMb = 2000,
    )

    private val streams: List<Stream> by lazy {
        val json = javaClass.classLoader!!.getResource("torrentio_interstellar.json")!!.readText()
        StremioJson.decodeFromString(StreamResponse.serializer(), json).streams
    }

    private fun parse(s: Stream, caps: DeviceCaps = weakBox, maxH: Int = 2160) =
        StreamCandidate.parse(s, addon, caps, maxH, 169, 200L shl 30)

    @Test fun parsesRealTorrentioResponse() {
        assertTrue(streams.size >= 30)
        val c = streams.map { parse(it) }
        val remux = c.first { it.stream.infoHash == "d8d05ebec7303f05a7243986e8c7d0e201b0ac0d" }
        assertEquals(2160, remux.height)
        assertTrue(remux.dv && remux.hdr10Plus && remux.remux)
        assertEquals(Codec.HEVC, remux.codec)
        assertEquals(271, remux.seeders)
        assertTrue(remux.sizeBytes!! > 90L shl 30)
        assertEquals("Atmos", remux.audio)
        assertEquals(Compat.WARN, remux.compat) // ~80 Mbps over P2P

        val av1 = c.first { it.stream.infoHash == "abf5451366b96d8c518a7f56820f7d247b11222d" }
        assertEquals(Codec.AV1, av1.codec)
        assertEquals(Compat.NO, av1.compat)
    }

    @Test fun bestPickIsPlayableAndReasonable() {
        val best = streams.map { parse(it) }.filter { it.compat != Compat.NO }.maxBy { it.score }
        assertEquals(2160, best.height)
        assertEquals(Codec.HEVC, best.codec)
        assertTrue("bitrate ${best.bitrateBps}", (best.bitrateBps ?: 0) < 45_000_000)
        assertTrue((best.seeders ?: 0) >= 20)
    }

    @Test fun dolbyVisionProfile5IsRejectedWithoutDvDecoder() {
        val p5 = Stream(name = "Torrentio\n4k DV", title = "Movie 2024 2160p WEB-DL DV DDP5.1 H.265-GRP\n👤 50 💾 15 GB", infoHash = "a".repeat(40))
        assertEquals(Compat.NO, parse(p5).compat)
        assertNotEquals(Compat.NO, parse(p5, weakBox.copyWith(dv = true)).compat)
    }

    @Test fun debridCachedBeatsTorrent() {
        val rd = Stream(name = "[RD+] Torrentio\n4k HDR", title = "Movie 2160p x265 HDR\n💾 20 GB", url = "https://example.com/x.mkv")
        val p2p = Stream(name = "Torrentio\n4k HDR", title = "Movie 2160p x265 HDR\n👤 500 💾 20 GB", infoHash = "b".repeat(40))
        assertTrue(parse(rd).debridCached)
        assertTrue(parse(rd).score > parse(p2p).score)
    }

    @Test fun respectsMaxQualityOn1080pDevice() {
        val tv1080 = weakBox.copyWith(uhd = false)
        val best = streams.map { parse(it, tv1080, 1080) }.filter { it.compat != Compat.NO }.maxBy { it.score }
        assertTrue("got ${best.height}", best.height <= 1080)
    }

    private fun DeviceCaps.copyWith(dv: Boolean = dolbyVision, uhd: Boolean = hevc.uhd) = DeviceCaps(
        hevc.copy(uhd = uhd), avc, av1, vp9, dv, displayHdr10, displayHlg, displayDv, display4k && uhd, lowRam, memoryClassMb, totalRamMb,
    )
}
