package app.devper.appplayer.data.platform

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import app.devper.appplayer.domain.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.asSource

/**
 * Shares the device's MediaStore (same source as the old `MediaUpnpService.initVideo/Audio/Image`),
 * but reads through content URIs so it works with scoped storage on Android 10+.
 */
class MediaStoreLibrary(private val context: Context) : MediaLibrary {

    override suspend fun scan(kinds: Set<MediaKind>): List<LocalMedia> = withContext(Dispatchers.IO) {
        buildList {
            if (MediaKind.VIDEO in kinds) addAll(query(MediaKind.VIDEO, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
            if (MediaKind.AUDIO in kinds) addAll(query(MediaKind.AUDIO, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI))
            if (MediaKind.IMAGE in kinds) addAll(query(MediaKind.IMAGE, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }
    }

    private fun query(kind: MediaKind, collection: Uri): List<LocalMedia> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            when (kind) {
                MediaKind.VIDEO -> {
                    add(MediaStore.Video.VideoColumns.ARTIST)
                    add(MediaStore.Video.VideoColumns.DURATION)
                    add(MediaStore.Video.VideoColumns.RESOLUTION)
                }
                MediaKind.AUDIO -> {
                    add(MediaStore.Audio.AudioColumns.ARTIST)
                    add(MediaStore.Audio.AudioColumns.ALBUM)
                    add(MediaStore.Audio.AudioColumns.DURATION)
                }
                else -> Unit
            }
        }.toTypedArray()
        val selection = if (kind == MediaKind.AUDIO) "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0" else null
        val out = mutableListOf<LocalMedia>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        }.getOrNull()?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
            fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            while (c.moveToNext()) {
                val id = long(MediaStore.MediaColumns._ID) ?: continue
                val name = str(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty()
                val mime = str(MediaStore.MediaColumns.MIME_TYPE) ?: MimeTypes.fromFileName(name) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                out += LocalMedia(
                    id = uri.toString(),
                    kind = kind,
                    title = str(MediaStore.MediaColumns.TITLE)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    artist = str(MediaStore.Audio.AudioColumns.ARTIST)?.takeIf { it != "<unknown>" },
                    album = str(MediaStore.Audio.AudioColumns.ALBUM),
                    mimeType = mime,
                    size = long(MediaStore.MediaColumns.SIZE) ?: 0L,
                    durationMs = long(MediaStore.Video.VideoColumns.DURATION),
                    resolution = str(MediaStore.Video.VideoColumns.RESOLUTION),
                    // keep the extension around for URLs: content://…/123#clip.mp4
                    source = "$uri#$name",
                )
            }
        }
        return out.filter { it.size > 0 }
    }

    override fun open(media: LocalMedia, offset: Long): RawSource {
        val uri = Uri.parse(media.source.substringBefore('#'))
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Cannot open $uri")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) stream.channel.position(offset)
        return stream.asSource()
    }
}
