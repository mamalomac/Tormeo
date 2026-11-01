package com.tormeo.app.data

import android.content.Context

/** Simple SharedPreferences-backed settings. Defaults are tuned per device class. */
class Settings(context: Context, private val isTv: Boolean) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Tunneled playback: decoder renders straight to the display pipeline. Big win on weak TV SoCs for 4K/HDR. */
    var tunneling: Boolean
        get() = p.getBoolean("tunneling", isTv)
        set(v) = p.edit().putBoolean("tunneling", v).apply()

    /** Switch the display refresh rate (and resolution) to match the video – removes 24p judder. */
    var matchFrameRate: Boolean
        get() = p.getBoolean("match_fps", isTv)
        set(v) = p.edit().putBoolean("match_fps", v).apply()

    /** Software (ffmpeg) audio decoding fallback for DTS / TrueHD / E-AC3 when the device has no decoder. */
    var ffmpegAudio: Boolean
        get() = p.getBoolean("ffmpeg_audio", true)
        set(v) = p.edit().putBoolean("ffmpeg_audio", v).apply()

    /** Hide streams this device cannot decode in hardware (AV1 without HW decoder, 4K on 1080p decoders, DV P5…). */
    var hideIncompatible: Boolean
        get() = p.getBoolean("hide_incompatible", true)
        set(v) = p.edit().putBoolean("hide_incompatible", v).apply()

    /** 0 = auto (by display + decoder), otherwise max vertical resolution (2160/1080/720). */
    var maxQuality: Int
        get() = p.getInt("max_quality", 0)
        set(v) = p.edit().putInt("max_quality", v).apply()

    /** Seconds of video downloaded before playback starts. */
    var prebufferSeconds: Int
        get() = p.getInt("prebuffer_s", 15)
        set(v) = p.edit().putInt("prebuffer_s", v).apply()

    /** Torrent: how much of the file ahead of the playhead may be downloaded (MB). */
    var torrentAheadMb: Int
        get() = p.getInt("torrent_ahead_mb", 1024)
        set(v) = p.edit().putInt("torrent_ahead_mb", v).apply()

    /** HTTP/debrid streams: size of the on-disk ring buffer (MB). */
    var httpBufferMb: Int
        get() = p.getInt("http_buffer_mb", 768)
        set(v) = p.edit().putInt("http_buffer_mb", v).apply()

    var maxConnections: Int
        get() = p.getInt("max_conn", if (isTv) 150 else 200)
        set(v) = p.edit().putInt("max_conn", v).apply()

    /** Upload limit in KB/s (0 = unlimited). Some upload is needed for good download speed. */
    var uploadLimitKb: Int
        get() = p.getInt("upload_kb", 0)
        set(v) = p.edit().putInt("upload_kb", v).apply()

    /** Keep downloaded torrent data after the player closes (otherwise it's deleted). */
    var keepCache: Boolean
        get() = p.getBoolean("keep_cache", false)
        set(v) = p.edit().putBoolean("keep_cache", v).apply()

    /** Preferred audio language (ISO 639-1, "" = device default). */
    var audioLang: String
        get() = p.getString("audio_lang", "") ?: ""
        set(v) = p.edit().putString("audio_lang", v).apply()

    /** Preferred subtitle language ("" = off unless forced). */
    var subLang: String
        get() = p.getString("sub_lang", "") ?: ""
        set(v) = p.edit().putString("sub_lang", v).apply()
}
