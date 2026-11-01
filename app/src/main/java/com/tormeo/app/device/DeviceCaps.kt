package com.tormeo.app.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display

/**
 * What this device can decode **in hardware** and what the display can show.
 * Used to hide streams that would fall back to software decoding (which stutters on weak SoCs).
 */
class DeviceCaps(
    val hevc: Codec,
    val avc: Codec,
    val av1: Codec,
    val vp9: Codec,
    val dolbyVision: Boolean,
    val displayHdr10: Boolean,
    val displayHlg: Boolean,
    val displayDv: Boolean,
    val display4k: Boolean,
    val lowRam: Boolean,
    val memoryClassMb: Int,
    val totalRamMb: Long,
) {

    data class Codec(val hw: Boolean = false, val uhd: Boolean = false, val tenBit: Boolean = false, val name: String? = null)

    val abi: String get() = Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"
    val is64Bit: Boolean get() = android.os.Process.is64Bit()

    /** Highest resolution worth picking automatically. */
    val autoMaxHeight: Int
        get() = if (display4k && (hevc.uhd || av1.uhd)) 2160 else 1080

    /** Can show HDR (otherwise Android tone-maps or shows washed-out colors). */
    val displayHdr: Boolean get() = displayHdr10 || displayHlg

    /** ExoPlayer RAM buffer – kept small, the real buffer lives on disk. */
    val playerBufferBytes: Int
        get() = when {
            lowRam || totalRamMb < 1200 -> 48
            memoryClassMb <= 256 -> 80
            memoryClassMb <= 512 -> 128
            else -> 192
        } * 1024 * 1024

    fun summary(): List<Pair<String, String>> {
        fun c(x: Codec) = if (!x.hw) "nema HW" else buildString {
            append(if (x.uhd) "4K" else "≤1080p")
            if (x.tenBit) append(" · 10-bit/HDR")
        }
        return listOf(
            "HEVC / H.265" to c(hevc),
            "H.264" to c(avc),
            "AV1" to c(av1),
            "VP9" to c(vp9),
            "Dolby Vision dekoder" to yes(dolbyVision),
            "Ekran 4K" to yes(display4k),
            "Ekran HDR10 / HLG / DV" to "${yes(displayHdr10)} / ${yes(displayHlg)} / ${yes(displayDv)}",
            "RAM" to "$totalRamMb MB${if (lowRam) " (low-RAM)" else ""}",
            "Arhitektura" to "$abi${if (is64Bit) " (64-bit)" else " (32-bit)"}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Uređaj" to "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.HARDWARE}",
        )
    }

    private fun yes(b: Boolean) = if (b) "da" else "ne"

    companion object {
        fun detect(context: Context): DeviceCaps {
            val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
                .getOrDefault(emptyList())
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val hdrTypes = if (Build.VERSION.SDK_INT >= 24) runCatching {
                @Suppress("DEPRECATION")
                display?.hdrCapabilities?.supportedHdrTypes?.toList()
            }.getOrNull() ?: emptyList() else emptyList()
            val displayDv = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            return DeviceCaps(
                hevc = probe(decoders, "video/hevc", intArrayOf(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10)),
                avc = probe(decoders, "video/avc", intArrayOf()),
                av1 = probe(decoders, "video/av01",
                    if (Build.VERSION.SDK_INT >= 29) intArrayOf(CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10) else intArrayOf()),
                vp9 = probe(decoders, "video/x-vnd.on2.vp9", intArrayOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile2HDR)),
                dolbyVision = decoders.any { d -> d.supportedTypes.any { it.equals("video/dolby-vision", true) } && isHw(d) },
                displayHdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes || displayDv,
                displayHlg = Display.HdrCapabilities.HDR_TYPE_HLG in hdrTypes,
                displayDv = displayDv,
                display4k = runCatching { display?.supportedModes?.any { it.physicalWidth >= 3800 || it.physicalHeight >= 3800 } }
                    .getOrNull() ?: false,
                lowRam = am.isLowRamDevice,
                memoryClassMb = am.largeMemoryClass,
                totalRamMb = mi.totalMem / (1024 * 1024),
            )
        }

        private fun isHw(info: MediaCodecInfo): Boolean {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
            val n = info.name.lowercase()
            return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("ffmpeg") ||
                n.contains(".sw.") || n.endsWith(".sw") || n.startsWith("omx.sec.") && n.contains("sw"))
        }

        private fun probe(decoders: List<MediaCodecInfo>, mime: String, tenBitProfiles: IntArray): Codec {
            var best = Codec()
            for (d in decoders) {
                if (!isHw(d)) continue
                val type = d.supportedTypes.firstOrNull { it.equals(mime, true) } ?: continue
                val caps = runCatching { d.getCapabilitiesForType(type) }.getOrNull() ?: continue
                val v = caps.videoCapabilities
                val uhd = v != null && runCatching { v.isSizeSupported(3840, 2160) || v.isSizeSupported(3840, 2096) }.getOrDefault(false)
                val tenBit = caps.profileLevels.any { it.profile in tenBitProfiles }
                val c = Codec(true, uhd, tenBit, d.name)
                if (!best.hw || (c.uhd && !best.uhd) || (c.uhd == best.uhd && c.tenBit && !best.tenBit)) best = c
            }
            return best
        }
    }
}
