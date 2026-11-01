package com.tormeo.app.stream

import com.tormeo.app.data.InstalledAddon
import com.tormeo.app.data.Stream
import com.tormeo.app.device.DeviceCaps
import kotlin.math.ln

enum class Codec(val label: String) { HEVC("HEVC"), AVC("H.264"), AV1("AV1"), VP9("VP9"), UNKNOWN("") }
enum class Compat { OK, WARN, NO }

/** A stream from an addon, parsed and scored for this device. */
data class StreamCandidate(
    val stream: Stream,
    val addon: InstalledAddon,
    val height: Int,
    val dv: Boolean,
    val hdr10: Boolean,
    val hdr10Plus: Boolean,
    val codec: Codec,
    val remux: Boolean,
    val sizeBytes: Long?,
    val seeders: Int?,
    val debridCached: Boolean,
    val isTorrent: Boolean,
    val bitrateBps: Long?,
    val audio: String?,
    val lowQualitySource: Boolean,
    val compat: Compat,
    val compatReason: String?,
    val score: Double,
) {
    val isHdr: Boolean get() = dv || hdr10 || hdr10Plus
    val filename: String? get() = stream.behaviorHints?.filename
    val qualityLabel: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            height > 0 -> "${height}p"
            else -> "?"
        }
    /** Main human readable text from the addon ("title" for Torrentio, "description" for newer addons). */
    val details: String get() = (stream.description ?: stream.title ?: "").trim()

    companion object {
        private val reRes = listOf(
            Regex("\\b(2160p|4k|uhd)\\b") to 2160,
            Regex("\\b1440p\\b") to 1440,
            Regex("\\b1080[pi]?\\b") to 1080,
            Regex("\\b720p?\\b") to 720,
            Regex("\\b(576p|480p|sd|dvdrip)\\b") to 480,
        )
        private val reDv = Regex("\\b(dolby ?vision|dovi|dv)\\b")
        private val reHdr10p = Regex("hdr10(\\+|plus|p)")
        private val reHdr = Regex("\\bhdr(10)?\\b")
        private val reHevc = Regex("\\b(x265|h\\.?265|hevc)\\b")
        private val reAvc = Regex("\\b(x264|h\\.?264|avc)\\b")
        private val reAv1 = Regex("\\bav1\\b")
        private val reVp9 = Regex("\\bvp9\\b")
        private val reSize = Regex("💾\\s*([\\d.,]+)\\s*(tb|gb|mb)|\\b([\\d.]+)\\s*(tb|gb|mb)\\b")
        private val reSeeders = Regex("👤\\s*(\\d+)")
        private val reDebrid = Regex("\\[(rd|ad|pm|dl|tb|ed|oc|pp|pk|dr)\\+\\]")
        private val reLowQ = Regex("\\b(cam|camrip|hdcam|ts|telesync|hdts|tc|telecine|scr|screener)\\b")
        private val reAudio = listOf("atmos" to "Atmos", "truehd" to "TrueHD", "dts-hd|dts:x|dtsx" to "DTS-HD", "\\bdts\\b" to "DTS",
            "ddp|dd\\+|eac3|e-ac-3" to "DD+", "\\b(dd|ac3)\\b" to "DD").map { Regex(it.first) to it.second }

        fun parse(
            stream: Stream,
            addon: InstalledAddon,
            caps: DeviceCaps,
            maxHeight: Int,
            runtimeMinutes: Int?,
            freeBytes: Long,
        ): StreamCandidate {
            val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename, stream.behaviorHints?.bingeGroup)
                .joinToString(" ").lowercase()
            val nameLower = (stream.name ?: "").lowercase()

            var height = reRes.firstOrNull { it.first.containsMatchIn(text) }?.second ?: 0
            val dv = reDv.containsMatchIn(text)
            val hdr10p = reHdr10p.containsMatchIn(text)
            val hdr10 = reHdr.containsMatchIn(text) || hdr10p
            var codec = when {
                reAv1.containsMatchIn(text) -> Codec.AV1
                reHevc.containsMatchIn(text) -> Codec.HEVC
                reVp9.containsMatchIn(text) -> Codec.VP9
                reAvc.containsMatchIn(text) -> Codec.AVC
                else -> Codec.UNKNOWN
            }
            // 4K / HDR without a codec tag is almost always HEVC
            if (codec == Codec.UNKNOWN && (height >= 2160 || dv || hdr10)) codec = Codec.HEVC
            if (height == 0 && (dv || hdr10)) height = 2160
            val remux = "remux" in text

            val size = stream.behaviorHints?.videoSize ?: reSize.find(text)?.let { m ->
                val num = (m.groups[1]?.value ?: m.groups[3]?.value)?.replace(",", ".")?.toDoubleOrNull() ?: return@let null
                val unit = m.groups[2]?.value ?: m.groups[4]?.value
                val mult = when (unit) { "tb" -> 1e12; "gb" -> 1024.0 * 1024 * 1024; else -> 1024.0 * 1024 }
                (num * mult).toLong()
            }
            val seeders = reSeeders.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val isTorrent = stream.infoHash != null && stream.url == null
            val debridCached = !isTorrent && (reDebrid.containsMatchIn(nameLower) || stream.url != null && "download" !in nameLower)
            val runtimeSec = (runtimeMinutes ?: if (height >= 2160) 130 else 110) * 60L
            val bitrate = size?.let { it * 8 / runtimeSec }
            val audio = reAudio.firstOrNull { it.first.containsMatchIn(text) }?.second
            val lowQ = reLowQ.containsMatchIn(text)

            // ---------------------------------------------------- compatibility
            var compat = Compat.OK
            var reason: String? = null
            fun no(r: String) { if (compat != Compat.NO) { compat = Compat.NO; reason = r } }
            fun warn(r: String) { if (compat == Compat.OK) { compat = Compat.WARN; reason = r } }

            when (codec) {
                Codec.HEVC -> {
                    if (!caps.hevc.hw) no("Nema HW HEVC dekodera")
                    else if (height >= 2160 && !caps.hevc.uhd) no("HEVC dekoder ne podržava 4K")
                    else if (hdr10 || dv) { if (!caps.hevc.tenBit) no("Dekoder ne podržava 10-bit (HDR)") }
                }
                Codec.AV1 -> {
                    if (!caps.av1.hw) no("Nema HW AV1 dekodera (softverski bi seckao)")
                    else if (height >= 2160 && !caps.av1.uhd) no("AV1 dekoder ne podržava 4K")
                }
                Codec.VP9 -> if (!caps.vp9.hw) no("Nema HW VP9 dekodera")
                Codec.AVC -> if (height >= 2160 && !caps.avc.uhd) no("H.264 dekoder ne podržava 4K")
                Codec.UNKNOWN -> Unit
            }
            // "DV" without an HDR10 base layer is usually profile 5 (streaming WEB-DL) → wrong (green/purple) colors without DV
            if (dv && !hdr10 && !remux && !caps.dolbyVision) no("Dolby Vision profil 5 – pogrešne boje bez DV podrške")
            if (stream.url == null && stream.infoHash == null) no("Eksterni link – ne može se pustiti u aplikaciji")
            if (isTorrent && size != null && size > freeBytes) warn("Malo slobodnog prostora (${fmtSize(freeBytes)})")
            if (isHdr(dv, hdr10) && !caps.displayHdr) warn("Ekran nema HDR – boje mogu biti blede")
            if (isTorrent && (bitrate ?: 0) > 60_000_000) warn("Vrlo visok bitrate za torrent")
            if (isTorrent && seeders != null && seeders < 3) warn("Malo seedera – moguće baferovanje")

            // ---------------------------------------------------- score
            var score = 0.0
            val effHeight = if (height == 0) 720 else height
            score += if (effHeight > maxHeight) -400.0 else effHeight / 5.0              // 2160 → 432, 1080 → 216
            if (dv && (caps.dolbyVision || hdr10) && caps.displayHdr) score += 30
            else if (hdr10 && caps.displayHdr) score += 25
            if (codec == Codec.HEVC) score += 15
            if (debridCached) score += 600                                                // direct HTTP: no peers needed
            if (isTorrent) {
                val s = seeders ?: 5
                score += ln(s + 1.0) * 45                                                  // 1→31, 10→108, 100→208
                if (s < 3) score -= 250
                val mbps = (bitrate ?: 0) / 1_000_000.0
                if (mbps > 35) score -= (mbps - 35) * 6                                    // remuxes are risky over P2P
            }
            if (lowQ) score -= 800
            if ("3d" in text.split(' ', '.', '-')) score -= 300
            when (compat) { Compat.NO -> score -= 10_000; Compat.WARN -> score -= 60; Compat.OK -> Unit }

            return StreamCandidate(stream, addon, height, dv, hdr10, hdr10p, codec, remux, size,
                seeders, debridCached, isTorrent, bitrate, audio, lowQ, compat, reason, score)
        }

        private fun isHdr(dv: Boolean, hdr10: Boolean) = dv || hdr10

        fun fmtSize(b: Long): String = when {
            b >= 1L shl 30 -> String.format("%.1f GB", b / (1024.0 * 1024 * 1024))
            else -> String.format("%.0f MB", b / (1024.0 * 1024))
        }
    }
}
