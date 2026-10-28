package com.appdevper.mediaplayer.data.server

import com.appdevper.mediaplayer.data.platform.LocalMedia
import com.appdevper.mediaplayer.data.platform.LocalSubtitle
import com.appdevper.mediaplayer.data.upnp.Didl
import com.appdevper.mediaplayer.data.upnp.DidlObject
import com.appdevper.mediaplayer.data.upnp.DidlResource
import com.appdevper.mediaplayer.data.upnp.DidlSubtitle
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.data.upnp.formatUpnpTime
import io.ktor.http.encodeURLPathPart

/**
 * The ContentDirectory tree published by the built-in server:
 *
 *     0 (root)
 *     ├── 1 Videos
 *     ├── 2 Music
 *     └── 3 Pictures
 *
 * Same layout as the original MediaServer app (`ContentTree.VIDEO_ID/AUDIO_ID/IMAGE_ID`).
 */
class ContentTree(media: List<LocalMedia>, private val baseUrl: String) {

    private class Node(val obj: DidlObject, val children: List<String>)

    private val nodes = HashMap<String, Node>()
    private val mediaById = HashMap<String, LocalMedia>()

    init {
        val groups = listOf(
            Triple(VIDEO_ID, "Videos", MediaKind.VIDEO),
            Triple(AUDIO_ID, "Music", MediaKind.AUDIO),
            Triple(IMAGE_ID, "Pictures", MediaKind.IMAGE),
        )
        val rootChildren = mutableListOf<String>()
        for ((containerId, title, kind) in groups) {
            val items = media.filter { it.kind == kind }
            if (items.isEmpty()) continue
            val childIds = items.mapIndexed { i, m ->
                val id = "$containerId-${i + 1}"
                mediaById[id] = m
                nodes[id] = Node(itemObject(id, containerId, m), emptyList())
                id
            }
            nodes[containerId] = Node(container(containerId, ROOT_ID, title, childIds.size), childIds)
            rootChildren += containerId
        }
        nodes[ROOT_ID] = Node(container(ROOT_ID, "-1", "Root", rootChildren.size), rootChildren)
    }

    val counts: Map<MediaKind, Int> = media.groupingBy { it.kind }.eachCount()

    fun metadata(id: String): DidlObject? = nodes[id]?.obj

    fun children(id: String): List<DidlObject>? = nodes[id]?.children?.mapNotNull { nodes[it]?.obj }

    /** Resolves `/media/{id}.{ext}` -> media. */
    fun mediaForPath(fileName: String): LocalMedia? = mediaById[fileName.substringBeforeLast('.')]

    /** Resolves `/subtitle/{id}/{n}/{file name}` -> the item's n-th subtitle. */
    fun subtitle(id: String, n: Int): LocalSubtitle? = mediaById[id]?.subtitles?.getOrNull(n)

    private fun container(id: String, parent: String, title: String, count: Int) = DidlObject(
        id = id, parentId = parent, title = title, upnpClass = "object.container.storageFolder",
        isContainer = true, childCount = count, artist = null, album = null, albumArtUri = null, resources = emptyList(),
    )

    private fun itemObject(id: String, parent: String, m: LocalMedia): DidlObject {
        val url = "$baseUrl/media/$id.${m.extension}"
        val cls = when (m.kind) {
            MediaKind.VIDEO -> "object.item.videoItem"
            MediaKind.AUDIO -> "object.item.audioItem.musicTrack"
            MediaKind.IMAGE -> "object.item.imageItem.photo"
            MediaKind.OTHER, MediaKind.FOLDER -> "object.item"
        }
        return DidlObject(
            id = id,
            parentId = parent,
            title = m.title,
            upnpClass = cls,
            isContainer = false,
            childCount = null,
            artist = m.artist,
            album = m.album,
            albumArtUri = if (m.kind == MediaKind.IMAGE) url else null,
            resources = listOf(
                DidlResource(
                    uri = url,
                    protocolInfo = Didl.protocolInfo(m.mimeType),
                    size = m.size,
                    duration = m.durationMs?.let { formatUpnpTime(it, withMillis = true) },
                    resolution = m.resolution,
                ),
            ),
            // The file name stays in the URL, so players can tell the language (Movie.th.srt).
            subtitles = m.subtitles.mapIndexed { n, s ->
                DidlSubtitle("$baseUrl/subtitle/$id/$n/${s.fileName.encodeURLPathPart()}", s.fileName.substringAfterLast('.').lowercase())
            },
        )
    }

    companion object {
        const val ROOT_ID = "0"
        const val VIDEO_ID = "1"
        const val AUDIO_ID = "2"
        const val IMAGE_ID = "3"
    }
}
