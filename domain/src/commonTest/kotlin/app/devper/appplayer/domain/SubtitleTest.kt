package app.devper.appplayer.domain

import app.devper.appplayer.domain.model.SubtitleCue
import app.devper.appplayer.domain.model.textAt
import app.devper.appplayer.domain.subtitle.SubtitleFiles
import app.devper.appplayer.domain.subtitle.SubtitleParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleTest {

    @Test
    fun readsSubRip() {
        val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Hello</i>\r\nworld\r\n\r\n2\r\n01:02:03,040 --> 01:02:05,000\r\n{\\an8}Up top &amp; more\r\n"
        assertEquals(
            listOf(SubtitleCue(1_000, 3_500, "Hello\nworld"), SubtitleCue(3_723_040, 3_725_000, "Up top & more")),
            SubtitleParser.parse(srt),
        )
    }

    @Test
    fun readsWebVtt() {
        val vtt = "WEBVTT\n\nNOTE made by hand\n\nintro\n00:05.000 --> 00:07.250 align:start\n<v Narrator>Once upon a time\n\n00:00:08.000 --> 00:00:09.000\nThe end\n"
        assertEquals(
            listOf(SubtitleCue(5_000, 7_250, "Once upon a time"), SubtitleCue(8_000, 9_000, "The end")),
            SubtitleParser.parse(vtt),
        )
    }

    @Test
    fun readsThaiFilesInEveryCommonEncoding() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nสวัสดี\n"
        val expected = listOf(SubtitleCue(1_000, 2_000, "สวัสดี"))
        assertEquals(expected, SubtitleParser.parse(text.encodeToByteArray()))
        assertEquals(expected, SubtitleParser.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.encodeToByteArray()))
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.flatMap { listOf((it.code and 0xFF).toByte(), (it.code shr 8).toByte()) }.toByteArray()
        assertEquals(expected, SubtitleParser.parse(utf16))
        // TIS-620: one byte per Thai letter, U+0E01 = 0xA1.
        val tis620 = text.map { c -> if (c.code in 0x0E01..0x0E5B) (c.code - 0x0D60).toByte() else c.code.toByte() }.toByteArray()
        assertEquals(expected, SubtitleParser.parse(tis620))
        // UTF-16 without a BOM, little-endian.
        assertEquals(expected, SubtitleParser.parse(utf16.copyOfRange(2, utf16.size)))
    }

    @Test
    fun otherLegacyFilesAreWindows1252NotThai() {
        // "Café déjà vu" in Windows-1252: é is 0xE9, which would be a Thai letter in TIS-620.
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nCaf".encodeToByteArray() + byteArrayOf(0xE9.toByte()) +
            " d".encodeToByteArray() + byteArrayOf(0xE9.toByte()) + "j".encodeToByteArray() + byteArrayOf(0xE0.toByte()) + " vu \u0093ok\u0094\n".encodeToByteArray().filter { it.toInt() != 0xC2.toByte().toInt() }.toByteArray()
        assertEquals("Café déjà vu “ok”", SubtitleParser.parse(bytes).single().text)
    }

    @Test
    fun findsEveryCueOnScreen() {
        val cues = listOf(SubtitleCue(1_000, 2_000, "a"), SubtitleCue(3_000, 10_000, "b"), SubtitleCue(4_000, 4_500, "c"), SubtitleCue(4_000, 4_500, "d"))
        assertNull(cues.textAt(500))
        assertEquals("a", cues.textAt(1_000))
        assertNull(cues.textAt(2_500)) // between cues
        assertEquals("b\nc\nd", cues.textAt(4_200)) // overlapping: all of them
        assertEquals("b", cues.textAt(5_000)) // the long one is still on after the short ones
        assertNull(emptyList<SubtitleCue>().textAt(0))
    }

    @Test
    fun assEventsFromFfmpegBecomePlainText() {
        assertEquals("Hello, there\nfriend", SubtitleParser.assText("0,0,Default,,0,0,0,,{\\i1}Hello, there{\\i0}\\Nfriend"))
        assertEquals("Hi", SubtitleParser.assText("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hi"))
    }

    @Test
    fun cuesWithoutABlankLineBetweenThemStayApart() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst\n2\n00:00:03,000 --> 00:00:04,000\nSecond\n3\n00:00:05,000 --> 00:00:06,000\nThird"
        assertEquals(listOf("First", "Second", "Third"), SubtitleParser.parse(srt).map { it.text })
    }

    @Test
    fun textThatOnlyLooksLikeMarkupStays() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\na < b > c &amp;lt;tag&amp;gt;\n"
        assertEquals("a < b > c &lt;tag&gt;", SubtitleParser.parse(srt).single().text)
    }

    @Test
    fun hostileFilesDontHang() {
        val digits = "1".repeat(2_000_000)
        val brackets = "<".repeat(200_000)
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        assertEquals(emptyList(), SubtitleParser.parse(digits))
        assertEquals(emptyList(), SubtitleParser.parse("1\n00:00:01,000 --> 00:00:02,000\n$brackets x").map { it.startMs }.drop(1))
        assertTrue(start.elapsedNow().inWholeSeconds < 5, "took ${start.elapsedNow()}")
    }

    @Test
    fun aSubtitleGoesToTheVideoItNamesMostClosely() {
        val files = listOf("Movie.mkv", "Movie.2020.mkv", "Movie.2020.th.srt", "Movie.HD.srt", "Movie.en.forced.srt", "Movie.a.b.srt")
        assertEquals(
            listOf(SubtitleFiles.Sidecar("Movie.en.forced.srt", "en"), SubtitleFiles.Sidecar("Movie.HD.srt", "HD")),
            SubtitleFiles.sidecars("Movie.mkv", files),
        )
        assertEquals(listOf(SubtitleFiles.Sidecar("Movie.2020.th.srt", "th")), SubtitleFiles.sidecars("Movie.2020.mkv", files))
        assertNull(SubtitleFiles.languageOf("http://h/Movie.HD.srt")) // not a language
        assertEquals("English", SubtitleFiles.languageOf("http://h/Movie.English.forced.srt")?.let { SubtitleFiles.label(it, "") })
    }

    @Test
    fun sidecarFilesGoWithTheirVideo() {
        val files = listOf("Movie.mkv", "Movie.srt", "movie.th.srt", "Movie.English.vtt", "Other.srt", "Movie.nfo", "Movie.2.mkv")
        assertEquals(
            listOf(
                SubtitleFiles.Sidecar("Movie.srt", null),
                SubtitleFiles.Sidecar("Movie.English.vtt", "English"),
                SubtitleFiles.Sidecar("movie.th.srt", "th"),
            ),
            SubtitleFiles.sidecars("Movie.mkv", files),
        )
        assertEquals("Thai", SubtitleFiles.label("th", "movie.th.srt"))
        assertEquals("Movie.srt", SubtitleFiles.label(null, "Movie.srt"))
        assertEquals("th", SubtitleFiles.languageOf("http://h/media/Movie.th.srt?x=1"))
        assertNull(SubtitleFiles.languageOf("Movie.srt"))
    }
}
