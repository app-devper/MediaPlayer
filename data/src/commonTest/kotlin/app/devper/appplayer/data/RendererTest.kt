package app.devper.appplayer.data

import app.devper.appplayer.data.renderer.MediaRenderer
import app.devper.appplayer.data.upnp.host.GenaPublisher
import app.devper.appplayer.domain.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RendererTest {

    @Test
    fun entryFromDidlMetadata() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="42" parentID="7" restricted="1"><dc:title>Song &amp; Dance</dc:title><upnp:artist>Band</upnp:artist>
            <upnp:class>object.item.audioItem.musicTrack</upnp:class>
            <res protocolInfo="http-get:*:audio/flac:*" duration="0:03:05.000">http://10.0.0.5/a.flac</res></item></DIDL-Lite>"""
        val e = MediaRenderer.entryFor("http://10.0.0.5:8080/stream?id=42", didl)!!
        assertEquals("Song & Dance", e.title)
        assertEquals("Band", e.artist)
        assertEquals(MediaKind.AUDIO, e.kind)
        assertEquals("audio/flac", e.mimeType)
        assertEquals(185_000L, e.durationMs)
        // The URI the controller asked for wins over the DIDL resource.
        assertEquals("http://10.0.0.5:8080/stream?id=42", e.streamUrl)
    }

    @Test
    fun entryGuessedFromUrlWithoutMetadata() {
        val e = MediaRenderer.entryFor("http://nas.local/movies/Big%20Trip.mkv", "")!!
        assertEquals(MediaKind.VIDEO, e.kind)
        assertEquals("video/x-matroska", e.mimeType)
        assertTrue(e.title.startsWith("Big"))
        assertEquals(MediaKind.IMAGE, MediaRenderer.entryFor("https://h/p/beach.JPG", "garbage <xml")!!.kind)
        assertEquals(MediaKind.VIDEO, MediaRenderer.entryFor("http://h/live", "")!!.kind) // unknown → try as video
    }

    @Test
    fun onlyHttpUrlsArePlayed() {
        assertNull(MediaRenderer.entryFor("file:///etc/passwd", ""))
        assertNull(MediaRenderer.entryFor("rtsp://cam/stream", ""))
        assertNull(MediaRenderer.entryFor("not a url", ""))
    }

    @Test
    fun genaCallbackMustPointBackAtTheSubscriber() {
        assertEquals("http://10.0.0.7:4000/cb", GenaPublisher.parseCallback("<http://10.0.0.7:4000/cb>", "10.0.0.7"))
        assertEquals(
            "http://10.0.0.7/b",
            GenaPublisher.parseCallback("<http://10.0.0.99/a><http://10.0.0.7/b>", "10.0.0.7"),
        )
        assertNull(GenaPublisher.parseCallback("<http://10.0.0.99/a>", "10.0.0.7"))
        assertNull(GenaPublisher.parseCallback("<https://10.0.0.7/a>", "10.0.0.7"))
        assertNull(GenaPublisher.parseCallback(null, "10.0.0.7"))
    }

    @Test
    fun genaTimeout() {
        assertEquals(300, GenaPublisher.parseTimeout("Second-300"))
        assertEquals(1800, GenaPublisher.parseTimeout("Second-infinite"))
        assertEquals(60, GenaPublisher.parseTimeout("Second-5"))
        assertEquals(1800, GenaPublisher.parseTimeout(null))
    }
}
