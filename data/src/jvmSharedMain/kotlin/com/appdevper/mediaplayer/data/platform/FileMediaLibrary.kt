package com.appdevper.mediaplayer.data.platform

import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.subtitle.SubtitleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.asSource
import java.io.File
import java.io.FileInputStream

/** Shares media files found (recursively) in a list of folders. Used on desktop. */
class FileMediaLibrary(private val folders: List<String>) : MediaLibrary {

    override suspend fun scan(kinds: Set<MediaKind>): List<LocalMedia> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        val folderFiles = HashMap<File, List<String>>()
        folders.map(::File).filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown()
                .onEnter { !it.isHidden && it.name != "node_modules" }
                .filter { it.isFile && !it.isHidden }
                .take(MAX_FILES)
                .mapNotNull { f ->
                    val mime = MimeTypes.fromFileName(f.name) ?: return@mapNotNull null
                    val kind = MimeTypes.kindOf(mime)
                    if (kind !in kinds || !seen.add(f.absolutePath)) return@mapNotNull null
                    LocalMedia(
                        id = f.absolutePath,
                        kind = kind,
                        title = f.nameWithoutExtension,
                        artist = null,
                        album = f.parentFile?.name,
                        mimeType = mime,
                        size = f.length(),
                        durationMs = null,
                        resolution = null,
                        source = f.absolutePath,
                        subtitles = if (kind == MediaKind.VIDEO) sidecars(f, folderFiles) else emptyList(),
                    )
                }.toList()
        }.sortedBy { it.title.lowercase() }
    }

    /** Subtitle files next to [video] (each folder listed once). */
    private fun sidecars(video: File, folderFiles: HashMap<File, List<String>>): List<LocalSubtitle> {
        val dir = video.parentFile ?: return emptyList()
        val names = folderFiles.getOrPut(dir) { dir.list()?.toList().orEmpty() }
        return SubtitleFiles.sidecars(video.name, names).map { LocalSubtitle(it.fileName, it.language, File(dir, it.fileName).absolutePath) }
    }

    override fun openSubtitle(subtitle: LocalSubtitle): RawSource = FileInputStream(subtitle.source).asSource()

    override fun open(media: LocalMedia, offset: Long): RawSource {
        val stream = FileInputStream(media.source)
        if (offset > 0) stream.channel.position(offset)
        return stream.asSource()
    }

    private companion object {
        const val MAX_FILES = 20_000
    }
}
