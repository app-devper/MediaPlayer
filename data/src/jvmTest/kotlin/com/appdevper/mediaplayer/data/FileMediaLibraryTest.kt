package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.platform.FileMediaLibrary
import com.appdevper.mediaplayer.data.platform.LocalSubtitle
import com.appdevper.mediaplayer.domain.model.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FileMediaLibraryTest {

    @Test
    fun videosComeWithTheSubtitleFilesNextToThem() = runBlocking {
        val dir = createTempDirectory("library").toFile()
        try {
            listOf("Movie.mp4", "Movie.th.srt", "Movie.srt", "Other.srt", "Song.mp3", "Song.srt").forEach { File(dir, it).writeText(it) }
            val library = FileMediaLibrary(listOf(dir.absolutePath))
            val media = library.scan(setOf(MediaKind.VIDEO, MediaKind.AUDIO)).associateBy { it.title }

            val subtitles = media.getValue("Movie").subtitles
            assertEquals(
                listOf(LocalSubtitle("Movie.srt", null, File(dir, "Movie.srt").absolutePath), LocalSubtitle("Movie.th.srt", "th", File(dir, "Movie.th.srt").absolutePath)),
                subtitles,
            )
            assertEquals(emptyList(), media.getValue("Song").subtitles) // songs don't get any
            assertEquals("Movie.th.srt", library.openSubtitle(subtitles[1]).buffered().readByteArray().decodeToString())
        } finally {
            dir.deleteRecursively()
        }
    }
}
