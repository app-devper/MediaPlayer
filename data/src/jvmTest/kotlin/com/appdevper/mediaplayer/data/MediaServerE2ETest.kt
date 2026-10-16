package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.platform.LocalMedia
import com.appdevper.mediaplayer.data.platform.MediaLibrary
import com.appdevper.mediaplayer.data.platform.localIpv4Address
import com.appdevper.mediaplayer.data.server.MediaServer
import com.appdevper.mediaplayer.data.server.ServerConfig
import com.appdevper.mediaplayer.data.server.ServerState
import com.appdevper.mediaplayer.data.upnp.ContentDirectoryClient
import com.appdevper.mediaplayer.data.upnp.DeviceDescription
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.data.upnp.SoapClient
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import org.junit.Assume.assumeNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Starts the real DLNA server on the LAN interface and talks to it with the control-point client. */
class MediaServerE2ETest {

    private val payload = ByteArray(10_000) { (it % 251).toByte() }

    private val library = object : MediaLibrary {
        override suspend fun scan(kinds: Set<MediaKind>) = listOf(
            LocalMedia("a", MediaKind.VIDEO, "Clip <1>", null, null, "video/mp4", payload.size.toLong(), 65_000, "1920x1080", "/x/clip.mp4"),
            LocalMedia("b", MediaKind.AUDIO, "Song", "Artist", "Album", "audio/mpeg", payload.size.toLong(), 1_000, null, "/x/song.mp3"),
        ).filter { it.kind in kinds }

        override fun open(media: LocalMedia, offset: Long): RawSource {
            val buf = Buffer()
            buf.write(payload, offset.toInt(), payload.size)
            return buf
        }
    }

    @Test
    fun browseAndStream() = runBlocking {
        assumeNotNull(localIpv4Address()) // needs a LAN interface
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient()
        val server = MediaServer(SsdpEngine(scope), scope)
        try {
            server.start(ServerConfig("Test & Server", "uuid:test-1", setOf(MediaKind.VIDEO, MediaKind.AUDIO)), library)
            val running = assertIs<ServerState.Running>(server.state.value)

            val location = "${running.url}/description.xml"
            val device = DeviceDescription.parse(http.get(location).bodyAsText(), location).single()
            assertEquals("Test & Server", device.friendlyName)
            assertTrue(device.isMediaServer)

            val cds = ContentDirectoryClient(SoapClient(http))
            val root = cds.browseChildren(device, "0").objects
            assertEquals(listOf("Videos", "Music"), root.map { it.title })

            val videos = cds.browseChildren(device, root[0].id).objects
            val clip = videos.single()
            assertEquals("Clip <1>", clip.title)
            assertEquals(MediaKind.VIDEO, clip.kind)
            assertEquals(65_000L, clip.durationMs)

            val full = http.get(clip.url!!).bodyAsBytes()
            assertTrue(full.contentEquals(payload))

            val partial = http.get(clip.url!!) { header("Range", "bytes=100-199") }
            assertEquals(206, partial.status.value)
            assertEquals("bytes 100-199/10000", partial.headers["Content-Range"])
            assertTrue(partial.bodyAsBytes().contentEquals(payload.copyOfRange(100, 200)))
        } finally {
            server.stop()
            http.close()
            scope.cancel()
        }
    }
}
