package com.tormeo.app.subs

import androidx.media3.common.MimeTypes
import com.tormeo.app.data.StremioJson
import com.tormeo.app.data.Subtitle
import com.tormeo.app.data.SubtitlesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.Charset

class SubtitlesTest {
    private val real: List<Pair<String, Subtitle>> by lazy {
        val json = javaClass.classLoader!!.getResource("opensubtitles_interstellar.json")!!.readText()
        StremioJson.decodeFromString(SubtitlesResponse.serializer(), json).subtitles.map { "OpenSubtitles v3" to it }
    }

    @Test fun langMapping() {
        assertEquals("sr", Subtitles.lang1("srp"))
        assertEquals("sr", Subtitles.lang1("scc"))
        assertEquals("hr", Subtitles.lang1("hrv"))
        assertEquals("srpski", Subtitles.langLabel("srp"))
        assertEquals("portugalski (BR)", Subtitles.langLabel("pob"))
        assertEquals(listOf("sr", "hr", "bs", "en"), Subtitles.preferredLangs("sr"))
        assertEquals(listOf("en"), Subtitles.preferredLangs("en"))
        assertEquals(emptyList<String>(), Subtitles.preferredLangs(""))
    }

    @Test fun ranksRealResponseSerbianFirst() {
        assertTrue(real.size > 50)
        val pref = Subtitles.preferredLangs("sr")
        val ranked = Subtitles.rank(real, pref, "Interstellar.2014.2160p.UHD.BluRay.x265-TERMiNAL.mkv", 23.976f)
        val pick = Subtitles.autoPick(ranked, pref)
        assertNotNull(pick)
        assertEquals("sr", pick!!.lang)
        // the sr/hr/bs group always comes before the English fallback, and anything else comes last
        val langs = ranked.map { it.lang }
        assertTrue(langs.lastIndexOf("bs") < langs.indexOf("en"))
        assertTrue(langs.lastIndexOf("hr") < langs.indexOf("en"))
        assertTrue(langs.lastIndexOf("en") < langs.indexOfFirst { it !in pref })
        assertEquals("sr", langs.first())
        // a 25 fps subtitle must not beat a 23.976 one of the same language
        val sr = ranked.filter { it.lang == "sr" }
        val firstWrongFps = sr.indexOfFirst { (it.sub.fpsMilli ?: 0) == 25000 }
        val firstRightFps = sr.indexOfFirst { (it.sub.fpsMilli ?: 0) == 23976 }
        if (firstWrongFps >= 0 && firstRightFps >= 0) assertTrue(firstRightFps < firstWrongFps)
    }

    @Test fun camSubtitlesRankedBelowForBluRay() {
        val subs = listOf(
            "os" to Subtitle(id = "1", url = "u1", lang = "eng", movieReleaseName = "Movie.2014.HDCAM.XviD", releaseFormat = "Cam"),
            "os" to Subtitle(id = "2", url = "u2", lang = "eng", movieReleaseName = "Movie.2014.1080p.BluRay.x264"),
        )
        val r = Subtitles.rank(subs, listOf("en"), "Movie.2014.2160p.BluRay.x265.mkv", null)
        assertEquals("2", r.first().sub.id)
    }

    @Test fun decodesUtf8AndLegacyCodepages() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nŠta ćeš, đače? Čekaj žabu.\n"
        assertEquals(srt, Subtitles.normalize(srt.toByteArray(Charsets.UTF_8), 25f).first)
        assertEquals(srt, Subtitles.normalize(srt.toByteArray(Charset.forName("windows-1250")), 25f).first)
        val cyr = "1\n00:00:01,000 --> 00:00:02,000\nЗдраво, шта радиш? Чекам те већ дуго.\n"
        assertEquals(cyr, Subtitles.normalize(cyr.toByteArray(Charset.forName("windows-1251")), 25f).first)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srt.toByteArray()
        assertEquals(srt, Subtitles.normalize(bom, 25f).first)
    }

    @Test fun detectsFormatsAndConvertsMicroDvd() {
        assertEquals(MimeTypes.TEXT_VTT, Subtitles.normalize("WEBVTT\n\n00:01.000 --> 00:02.000\nhi\n".toByteArray(), 25f).second)
        assertEquals(MimeTypes.TEXT_SSA, Subtitles.normalize("[Script Info]\nTitle: x\n\n[Events]\n".toByteArray(), 25f).second)
        val (text, mime) = Subtitles.normalize("{1}{1}25.000\n{25}{75}Prvi red|drugi red\n{100}{150}{y:i}Kraj\n".toByteArray(), 25f)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, mime)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,000\nPrvi red\ndrugi red\n\n2\n00:00:04,000 --> 00:00:06,000\nKraj\n\n",
            text,
        )
    }

    @Test(expected = IOException::class)
    fun rejectsGarbage() {
        Subtitles.normalize("<html>not a subtitle</html>".toByteArray(), 25f)
    }
}
