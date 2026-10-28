package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.mapper.toDomain
import com.appdevper.mediaplayer.data.server.MediaServer
import com.appdevper.mediaplayer.data.upnp.DeviceDescription
import com.appdevper.mediaplayer.data.upnp.Didl
import com.appdevper.mediaplayer.data.upnp.ServerAddress
import com.appdevper.mediaplayer.data.upnp.Soap
import com.appdevper.mediaplayer.data.upnp.SsdpMessage
import com.appdevper.mediaplayer.data.upnp.UpnpException
import com.appdevper.mediaplayer.data.upnp.Xml
import com.appdevper.mediaplayer.data.upnp.formatUpnpTime
import com.appdevper.mediaplayer.data.upnp.parseUpnpTime
import com.appdevper.mediaplayer.data.upnp.resolveUrl
import com.appdevper.mediaplayer.domain.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpnpTest {

    @Test
    fun xmlParsesNamespacesEntitiesAndCdata() {
        val root = Xml.parse(
            """<?xml version="1.0"?><!-- c --><s:Envelope xmlns:s="x"><s:Body>
              <u:R a='1'><Result>&lt;DIDL&gt; &amp; &#65;&#x42;</Result><e/><c><![CDATA[<raw>]]></c></u:R>
              </s:Body></s:Envelope>""",
        )
        val r = root.find("R")!!
        assertEquals("1", r.attr("a"))
        assertEquals("<DIDL> & AB", r.childText("Result"))
        assertEquals("<raw>", r.childText("c"))
        assertEquals("", r.childText("e"))
    }

    @Test
    fun deviceDescriptionResolvesRelativeUrlsAndEmbeddedDevices() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Living Room TV</friendlyName><manufacturer>Samsung</manufacturer>
              <UDN>uuid:tv-1</UDN>
              <iconList><icon><mimetype>image/png</mimetype><width>48</width><url>/i48.png</url></icon>
                        <icon><mimetype>image/png</mimetype><width>120</width><url>icons/i120.png</url></icon></iconList>
              <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>id</serviceId><controlURL>/avt/control</controlURL><eventSubURL>/avt/event</eventSubURL><SCPDURL>avt.xml</SCPDURL></service></serviceList>
              <deviceList><device><deviceType>x</deviceType><friendlyName>Sub</friendlyName><UDN>uuid:sub</UDN></device></deviceList>
            </device></root>
        """.trimIndent()
        val devices = DeviceDescription.parse(xml, "http://192.168.1.20:9197/dmr/desc.xml")
        assertEquals(2, devices.size)
        val tv = devices[0]
        assertTrue(tv.isRenderer)
        assertEquals("http://192.168.1.20:9197/avt/control", tv.services[0].controlUrl)
        assertEquals("http://192.168.1.20:9197/dmr/avt.xml", tv.services[0].scpdUrl)
        assertEquals("http://192.168.1.20:9197/dmr/icons/i120.png", tv.iconUrl)
        assertEquals("192.168.1.20", tv.host)
    }

    @Test
    fun didlRoundTrip() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="64" parentID="0" childCount="3" restricted="1"><dc:title>Music</dc:title><upnp:class>object.container</upnp:class></container>
            <item id="64$1" parentID="64" restricted="1"><dc:title>Song &amp; Dance</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>
              <upnp:artist>Band</upnp:artist><upnp:albumArtURI>http://h/a.jpg</upnp:albumArtURI>
              <res protocolInfo="http-get:*:audio/mpeg:*" size="1234" duration="0:03:25.500">http://h/s.mp3?x=1&amp;y=2</res></item>
            </DIDL-Lite>"""
        val objs = Didl.parse(didl)
        assertEquals(2, objs.size)
        assertTrue(objs[0].isContainer)
        assertEquals(3, objs[0].childCount)
        val song = objs[1]
        assertEquals("Song & Dance", song.title)
        assertEquals(MediaKind.AUDIO, song.kind)
        assertEquals("http://h/s.mp3?x=1&y=2", song.url)
        assertEquals(205_500L, song.durationMs)
        assertEquals("http://h/a.jpg", song.thumbnailUrl)

        val again = Didl.parse(Didl.generate(objs))
        assertEquals(objs, again)
    }

    @Test
    fun subtitlesFromOtherServersAndOurs() {
        // A subtitle res listed first (some servers do) and Samsung's CaptionInfoEx; the video still plays.
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"
            xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/">
            <item id="7" parentID="1"><dc:title>Movie</dc:title><upnp:class>object.item.videoItem</upnp:class>
            <res protocolInfo="http-get:*:text/srt:*">http://nas/sub/Movie.en.srt</res>
            <res protocolInfo="http-get:*:video/x-matroska:*">http://nas/v/Movie.mkv</res>
            <sec:CaptionInfoEx sec:type="srt">http://nas/sub/Movie.th.srt</sec:CaptionInfoEx>
            <sec:CaptionInfoEx sec:type="srt">http://nas/sub/Movie.en.srt</sec:CaptionInfoEx>
            <sec:CaptionInfo sec:type="srt">file:///etc/passwd</sec:CaptionInfo>
            </item></DIDL-Lite>"""
        val movie = Didl.parse(didl).single()
        assertEquals("http://nas/v/Movie.mkv", movie.url)
        assertEquals(MediaKind.VIDEO, movie.kind)
        assertEquals(listOf("http://nas/sub/Movie.en.srt", "http://nas/sub/Movie.th.srt"), movie.subtitles.map { it.uri }) // web addresses only, once each
        assertEquals(listOf("English", "Thai"), movie.toDomain().subtitles.map { it.label })

        // What our server writes, both ways, reads back the same.
        val again = Didl.parse(Didl.generate(listOf(movie))).single()
        assertEquals(movie.subtitles, again.subtitles)
        assertEquals(movie.url, again.url)
        assertTrue("sec:CaptionInfoEx" in Didl.generate(listOf(movie)))
    }

    @Test
    fun soapFaultBecomesException() {
        val e = assertFailsWith<UpnpException> { Soap.parseResponse(Soap.fault(701, "No such object")) }
        assertEquals(701, e.code)
        assertEquals("No such object", e.message)
    }

    @Test
    fun ssdpParsing() {
        val raw = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 900\r\nLocation: http://10.0.0.5:8200/rootDesc.xml\r\n" +
            "USN: uuid:4d696e69::urn:schemas-upnp-org:device:MediaServer:1\r\nST: upnp:rootdevice\r\n\r\n"
        val m = SsdpMessage.parse(raw.encodeToByteArray(), "10.0.0.5", 1900)!!
        assertTrue(m.isResponse)
        assertEquals("http://10.0.0.5:8200/rootDesc.xml", m.header("location"))
        assertEquals("uuid:4d696e69", m.udn)
        assertEquals(900, m.maxAgeSeconds)
    }

    @Test
    fun timeHelpers() {
        assertEquals(3_723_000L, parseUpnpTime("1:02:03"))
        assertEquals(1_250L, parseUpnpTime("0:00:01.25"))
        assertNull(parseUpnpTime("NOT_IMPLEMENTED"))
        assertEquals("1:02:03", formatUpnpTime(3_723_000L))
        assertEquals("0:00:01.250", formatUpnpTime(1_250L, withMillis = true))
    }

    @Test
    fun urlResolution() {
        assertEquals("http://a:1/x/y", resolveUrl("http://a:1/desc.xml", "/x/y"))
        assertEquals("http://a:1/d/x", resolveUrl("http://a:1/d/desc.xml", "x"))
        assertEquals("http://b/z", resolveUrl("http://a:1/d/desc.xml", "http://b/z"))
    }

    @Test
    fun byteRanges() {
        assertEquals(0L..99L, MediaServer.parseRange("bytes=0-99", 1000))
        assertEquals(500L..999L, MediaServer.parseRange("bytes=500-", 1000))
        assertEquals(900L..999L, MediaServer.parseRange("bytes=-100", 1000))
        assertEquals(990L..999L, MediaServer.parseRange("bytes=990-5000", 1000))
        assertNull(MediaServer.parseRange("bytes=1000-", 1000))
        assertNull(MediaServer.parseRange("bytes=abc", 1000))
    }

    @Test
    fun serverAddressesTheUserTypes() {
        val host = assertNotNull(ServerAddress.parse(" 192.168.1.20 "))
        assertEquals("192.168.1.20", host.host)
        // A bare host is also tried on this app's own server ports.
        assertEquals((8191..8194).map { "http://192.168.1.20:$it/description.xml" }, host.descriptionUrls)
        assertTrue(host.matches("http://192.168.1.20:49152/rootDesc.xml"))
        assertFalse(host.matches("http://192.168.1.21:8191/description.xml"))

        assertEquals("192.168.1.20", host.searchHost) // an IP gets the unicast M-SEARCH
        val name = assertNotNull(ServerAddress.parse("nas.local:5000"))
        assertEquals(listOf("http://nas.local:5000/description.xml"), name.descriptionUrls)
        assertNull(name.searchHost) // a name is only fetched (its LOCATION keeps the name)
        assertNull(ServerAddress.parse("192.168.1.300")?.searchHost)

        val url = assertNotNull(ServerAddress.parse("http://192.168.1.5:9000/dms/desc.xml"))
        assertEquals(null, url.host) // fetched as is, no search
        assertEquals(listOf("http://192.168.1.5:9000/dms/desc.xml"), url.descriptionUrls)
        assertTrue(url.matches("http://192.168.1.5:9000/dms/desc.xml"))

        // Same machine, whatever the port or the letter case.
        assertTrue(host.sameHost(assertNotNull(ServerAddress.parse("192.168.1.20:8191"))))
        assertTrue(name.sameHost(assertNotNull(ServerAddress.parse("http://NAS.local:9000/desc.xml"))))
        assertFalse(host.sameHost(assertNotNull(ServerAddress.parse("192.168.1.21"))))

        for (bad in listOf("", "  ", "192.168.1.20:0", "192.168.1.20:70000", "host:port", "a b", "x/y", "user@host", "http://")) {
            assertNull(ServerAddress.parse(bad), bad)
        }
    }
}
