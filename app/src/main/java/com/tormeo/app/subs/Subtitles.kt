package com.tormeo.app.subs

import androidx.media3.common.MimeTypes
import com.tormeo.app.data.Subtitle
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

/** A subtitle offered to the user, already ranked for the current video. */
data class SubtitleOption(
    val sub: Subtitle,
    val addonName: String,
    /** ISO 639-1 when known ("sr"), otherwise the addon's code. */
    val lang: String,
    val score: Double,
) {
    val langLabel: String get() = Subtitles.langLabel(sub.lang)
    val release: String get() = sub.movieReleaseName?.takeIf { it.isNotBlank() } ?: sub.subtitleFileName ?: addonName
    val title: String get() = "$langLabel · $release"
}

object Subtitles {

    private val iso3to1 = mapOf(
        "srp" to "sr", "scc" to "sr", "hrv" to "hr", "scr" to "hr", "bos" to "bs", "eng" to "en", "ger" to "de", "deu" to "de",
        "fre" to "fr", "fra" to "fr", "spa" to "es", "spn" to "es", "ita" to "it", "por" to "pt", "pob" to "pt", "rus" to "ru",
        "tur" to "tr", "hun" to "hu", "ron" to "ro", "rum" to "ro", "bul" to "bg", "ell" to "el", "gre" to "el", "pol" to "pl",
        "cze" to "cs", "ces" to "cs", "slv" to "sl", "slo" to "sk", "slk" to "sk", "mac" to "mk", "mkd" to "mk", "alb" to "sq",
        "sqi" to "sq", "ara" to "ar", "nld" to "nl", "dut" to "nl", "dan" to "da", "swe" to "sv", "nor" to "no", "fin" to "fi",
        "heb" to "he", "chi" to "zh", "zho" to "zh", "zht" to "zh", "zhs" to "zh", "jpn" to "ja", "kor" to "ko", "per" to "fa",
        "fas" to "fa", "ukr" to "uk", "vie" to "vi", "ind" to "id", "tha" to "th", "est" to "et", "lav" to "lv", "lit" to "lt",
    )

    private val labels = mapOf(
        "sr" to "srpski", "hr" to "hrvatski", "bs" to "bosanski", "en" to "engleski", "de" to "nemački", "fr" to "francuski",
        "es" to "španski", "it" to "italijanski", "pt" to "portugalski", "ru" to "ruski", "tr" to "turski", "hu" to "mađarski",
        "ro" to "rumunski", "bg" to "bugarski", "el" to "grčki", "pl" to "poljski", "cs" to "češki", "sl" to "slovenački",
        "sk" to "slovački", "mk" to "makedonski", "sq" to "albanski", "ar" to "arapski", "nl" to "holandski", "da" to "danski",
        "sv" to "švedski", "no" to "norveški", "fi" to "finski", "he" to "hebrejski", "zh" to "kineski", "ja" to "japanski",
        "ko" to "korejski", "fa" to "persijski", "uk" to "ukrajinski",
    )

    fun lang1(code: String): String {
        val c = code.lowercase()
        return if (c.length == 2) c else iso3to1[c] ?: c
    }

    fun langLabel(code: String): String {
        val c = code.lowercase()
        if (c == "pob") return "portugalski (BR)"
        return labels[lang1(c)] ?: code.uppercase()
    }

    /** Preferred language order for a setting: Serbian, Croatian and Bosnian are mutually understandable. */
    fun preferredLangs(pref: String): List<String> {
        val base = when (pref) {
            "sr" -> listOf("sr", "hr", "bs")
            "hr" -> listOf("hr", "bs", "sr")
            "bs" -> listOf("bs", "hr", "sr")
            "" -> emptyList()
            else -> listOf(pref)
        }
        return if (base.isEmpty() || "en" in base) base else base + "en"
    }

    private fun tokens(s: String?): Set<String> =
        s.orEmpty().lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in STOP }.toSet()

    private val STOP = setOf("mkv", "mp4", "avi", "srt", "sub", "the", "and", "www", "com")
    private val BAD_SOURCES = Regex("cam|telesync|telecine|screener|hdts|workprint", RegexOption.IGNORE_CASE)

    /** Ranks subtitles: preferred language, then release-name similarity, then matching FPS; CAM/TS rips last. */
    fun rank(subs: List<Pair<String, Subtitle>>, prefLangs: List<String>, videoName: String?, videoFps: Float?): List<SubtitleOption> {
        val vt = tokens(videoName)
        val videoIsBad = videoName != null && BAD_SOURCES.containsMatchIn(videoName)
        return subs.distinctBy { it.second.url }.map { (addon, s) ->
            val l = lang1(s.lang)
            var score = 0.0
            val li = prefLangs.indexOf(l)
            // Within the chosen language group (e.g. sr/hr/bs) a well-synced subtitle may beat a closer dialect;
            // the English fallback never beats the group.
            if (li >= 0) score += if (li > 0 && prefLangs[li] == "en") 5_000.0 else 10_000.0 - li * 100
            val st = tokens(s.movieReleaseName) + tokens(s.subtitleFileName)
            if (vt.isNotEmpty() && st.isNotEmpty()) score += minOf(120.0, (vt intersect st).size * 15.0)
            val fmt = listOfNotNull(s.releaseFormat, s.releaseOther, s.movieReleaseName).joinToString(" ")
            if (!videoIsBad && BAD_SOURCES.containsMatchIn(fmt)) score -= 80
            val fps = s.fpsMilli?.takeIf { it > 0 }?.div(1000f)
            if (fps != null && videoFps != null && videoFps > 0f) score += if (abs(fps - videoFps) < 0.05f) 40 else -90
            SubtitleOption(s, addon, l, score)
        }.sortedByDescending { it.score }
    }

    /** Best automatic choice for [prefLangs], or null if nothing in those languages. */
    fun autoPick(ranked: List<SubtitleOption>, prefLangs: List<String>): SubtitleOption? =
        ranked.firstOrNull { it.lang in prefLangs }

    // ------------------------------------------------------------------ download & normalize

    data class Prepared(val file: File, val mimeType: String)

    fun download(http: OkHttpClient, option: SubtitleOption, dir: File, videoFps: Float?): Prepared {
        val bytes = http.newCall(Request.Builder().url(option.sub.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.bytes() ?: throw IOException("Prazan titl")
        }
        val fps = videoFps?.takeIf { it > 0 } ?: option.sub.fpsMilli?.takeIf { it > 0 }?.div(1000f) ?: 23.976f
        val (text, mime) = normalize(bytes, fps)
        dir.mkdirs()
        val ext = when (mime) { MimeTypes.TEXT_VTT -> "vtt"; MimeTypes.TEXT_SSA -> "ass"; else -> "srt" }
        val f = File(dir, "sub-${option.sub.id.ifEmpty { option.sub.url.hashCode().toString() }}.$ext")
        f.writeText(text, Charsets.UTF_8)
        return Prepared(f, mime)
    }

    private val SRT_TIME = Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->")
    private val MICRODVD = Regex("^\\{(\\d+)\\}\\{(\\d*)\\}(.*)$")

    /** Decodes (UTF-8, else Windows-1250/1251), detects the format and converts MicroDVD to SRT. */
    fun normalize(bytes: ByteArray, fps: Float): Pair<String, String> {
        var text = decode(bytes).removePrefix("\uFEFF")
        val head = text.trimStart()
        val mime = when {
            head.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
            head.startsWith("[Script Info]") || "\n[Events]" in text -> MimeTypes.TEXT_SSA
            SRT_TIME.containsMatchIn(text) -> MimeTypes.APPLICATION_SUBRIP
            text.lineSequence().take(20).any { MICRODVD.matches(it.trim()) } -> {
                text = microDvdToSrt(text, fps)
                MimeTypes.APPLICATION_SUBRIP
            }
            else -> throw IOException("Nepoznat format titla")
        }
        return text to mime
    }

    private fun decode(bytes: ByteArray): String {
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
        }
        // Not UTF-8: Cyrillic text is mostly high bytes, Latin text with diacritics only sparsely.
        var high = 0
        var asciiLetters = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v >= 0xC0) high++ else if (v in 0x41..0x5A || v in 0x61..0x7A) asciiLetters++
        }
        val cs = if (high > asciiLetters) "windows-1251" else "windows-1250"
        return String(bytes, Charset.forName(cs))
    }

    private fun microDvdToSrt(text: String, fps: Float): String {
        val sb = StringBuilder()
        var n = 1
        for (raw in text.lineSequence()) {
            val m = MICRODVD.matchEntire(raw.trim()) ?: continue
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull() ?: (start + (fps * 3).toLong())
            if (start == 1L && end == 1L) continue // "{1}{1}23.976" fps header line
            val body = m.groupValues[3].replace(Regex("\\{[^}]*\\}"), "").split('|').joinToString("\n") { it.trim() }
            sb.append(n++).append('\n')
                .append(srtTime(start * 1000 / fps)).append(" --> ").append(srtTime(end * 1000 / fps)).append('\n')
                .append(body).append("\n\n")
        }
        if (n == 1) throw IOException("Prazan MicroDVD titl")
        return sb.toString()
    }

    private fun srtTime(msF: Float): String {
        val ms = msF.toLong()
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
