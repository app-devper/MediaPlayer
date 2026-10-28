package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.domain.model.Subtitle
import com.appdevper.mediaplayer.domain.model.SubtitleCue
import com.appdevper.mediaplayer.domain.model.at
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.domain.subtitle.SubtitleFiles
import com.appdevper.mediaplayer.domain.subtitle.SubtitleParser
import com.appdevper.mediaplayer.domain.usecase.LoadSubtitlesUseCase
import kotlinx.coroutines.test.runTest
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
    }

    @Test
    fun findsTheCueOnScreen() {
        val cues = listOf(SubtitleCue(1_000, 2_000, "a"), SubtitleCue(3_000, 5_000, "b"), SubtitleCue(4_000, 4_500, "c"))
        assertNull(cues.at(500))
        assertEquals("a", cues.at(1_000)?.text)
        assertNull(cues.at(2_500)) // between cues
        assertEquals("b", cues.at(3_999)?.text)
        assertEquals("c", cues.at(4_200)?.text)
        assertNull(emptyList<SubtitleCue>().at(0))
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

    @Test
    fun loadingFailsWhenTheFileHasNoCues() = runTest {
        val repo = object : SubtitleRepository {
            override suspend fun fetch(url: String) = if (url.endsWith("good.srt")) "1\n00:00:01,000 --> 00:00:02,000\nHi\n".encodeToByteArray() else "not subtitles".encodeToByteArray()
        }
        val load = LoadSubtitlesUseCase(repo)
        assertEquals(1, load(Subtitle("http://h/good.srt", "good")).getOrThrow().size)
        assertTrue(load(Subtitle("http://h/bad.srt", "bad")).isFailure)
    }
}
