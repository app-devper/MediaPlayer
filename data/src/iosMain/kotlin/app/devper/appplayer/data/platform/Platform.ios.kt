@file:OptIn(ExperimentalForeignApi::class)

package app.devper.appplayer.data.platform

import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.subtitle.SubtitleFiles
import app.devper.appplayer.domain.model.ServerCapabilities
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko

actual val platformName: String = "iOS"

actual fun defaultSharedFolders(): List<String> = emptyList()

private fun documentsDir(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String).orEmpty()

class DocumentsLibraryFactory : MediaLibraryFactory {
    override val capabilities = ServerCapabilities(
        canChooseFolders = false,
        sourceDescription = "files in Files › On My iPhone › Media Player",
    )
    override fun create(folders: List<String>): MediaLibrary = DocumentsLibrary()
}

/**
 * Shares files in the app's Documents folder (visible in the Files app / Finder because
 * UIFileSharingEnabled + LSSupportsOpeningDocumentsInPlace are set in Info.plist).
 */
private class DocumentsLibrary : MediaLibrary {
    override suspend fun scan(kinds: Set<MediaKind>): List<LocalMedia> = withContext(Dispatchers.IO) {
        val out = mutableListOf<LocalMedia>()
        fun walk(dir: Path, depth: Int) {
            if (depth > 8) return
            val children = runCatching { SystemFileSystem.list(dir) }.getOrDefault(emptyList())
            val names = children.map { it.name }
            for (p in children) {
                if (p.name.startsWith(".")) continue
                val meta = SystemFileSystem.metadataOrNull(p) ?: continue
                if (meta.isDirectory) {
                    walk(p, depth + 1)
                    continue
                }
                val mime = MimeTypes.fromFileName(p.name) ?: continue
                val kind = MimeTypes.kindOf(mime)
                if (kind !in kinds) continue
                out += LocalMedia(
                    id = p.toString(),
                    kind = kind,
                    title = p.name.substringBeforeLast('.'),
                    artist = null,
                    album = p.parent?.name,
                    mimeType = mime,
                    size = meta.size,
                    durationMs = null,
                    resolution = null,
                    source = p.toString(),
                    subtitles = if (kind == MediaKind.VIDEO) {
                        SubtitleFiles.sidecars(p.name, names).map { LocalSubtitle(it.fileName, it.language, Path(dir, it.fileName).toString()) }
                    } else {
                        emptyList()
                    },
                )
            }
        }
        walk(Path(documentsDir()), 0)
        out.sortedBy { it.title.lowercase() }
    }

    override fun open(media: LocalMedia, offset: Long): RawSource = PosixFileSource(media.source, offset)

    override fun openSubtitle(subtitle: LocalSubtitle): RawSource = PosixFileSource(subtitle.source, 0)
}

/** RawSource over stdio with an initial seek (kotlinx-io has no seekable source yet). */
private class PosixFileSource(path: String, offset: Long) : RawSource {
    private val fp = fopen(path, "rb") ?: throw IOException("Cannot open $path")
    private val chunk = ByteArray(64 * 1024)

    init {
        if (offset > 0 && fseeko(fp, offset, SEEK_SET) != 0) {
            fclose(fp)
            throw IOException("Cannot seek $path to $offset")
        }
    }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val want = minOf(byteCount, chunk.size.toLong()).toInt()
        if (want <= 0) return 0
        val read = chunk.usePinned { fread(it.addressOf(0), 1u, want.convert(), fp) }.toInt()
        if (read <= 0) return -1
        sink.write(chunk, 0, read)
        return read.toLong()
    }

    override fun close() {
        fclose(fp)
    }
}
