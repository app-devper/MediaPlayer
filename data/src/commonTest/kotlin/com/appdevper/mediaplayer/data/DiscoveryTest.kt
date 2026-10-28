package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.repository.RendererRepositoryImpl
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.data.upnp.SoapClient
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.data.upnp.UpnpDevice
import com.appdevper.mediaplayer.domain.model.MediaDevice
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The control point against an in-memory LAN: what it finds, keeps and drops, in virtual time. */
class DiscoveryTest {

    private val group = SsdpEngine.SSDP_ADDRESS

    private fun description(name: String) = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
          <friendlyName>$name</friendlyName><UDN>uuid:tv-1</UDN>
          <serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
            <controlURL>/avt/control</controlURL><eventSubURL>/avt/event</eventSubURL><SCPDURL>/avt.xml</SCPDURL>
          </service></serviceList>
        </device></root>"""

    private fun alive(location: String, maxAge: Int = 1800) =
        "NOTIFY * HTTP/1.1\r\nHOST: $group:1900\r\nCACHE-CONTROL: max-age=$maxAge\r\nLOCATION: $location\r\n" +
            "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\nNTS: ssdp:alive\r\nUSN: uuid:tv-1::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    private val byebye =
        "NOTIFY * HTTP/1.1\r\nHOST: $group:1900\r\nNT: upnp:rootdevice\r\nNTS: ssdp:byebye\r\nUSN: uuid:tv-1::upnp:rootdevice\r\n\r\n"

    /** A LAN with this device at 10.0.0.2, and a TV whose description is at each of [descriptions]. */
    private inner class Setup(val test: TestScope, descriptions: Map<String, String>) {
        val lan = InMemoryLan()
        val requests = mutableListOf<String>()
        val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(test.testScheduler)
                    addHandler { request ->
                        val url = request.url.toString()
                        requests += url
                        when {
                            url in descriptions -> respond(descriptions.getValue(url))
                            url.endsWith("/avt/control") -> respond(
                                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:PlayResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"/></s:Body></s:Envelope>",
                            )
                            else -> respondError(HttpStatusCode.NotFound)
                        }
                    }
                },
            ),
        )
        val scope: CoroutineScope = test.backgroundScope
        val ssdp = SsdpEngine(scope, network = lan.host("10.0.0.2"))
        val controlPoint = ControlPoint(ssdp, http, scope, time = test.testScheduler.timeSource)

        fun announce(text: String, from: String) = lan.send(text, from, group, 1900)
        val names: List<String> get() = controlPoint.devices.value.map(UpnpDevice::friendlyName)
    }

    @Test
    fun anAnnouncedRendererIsFoundAndLeavesWithItsByebye() = runTest {
        val s = Setup(this, mapOf("http://10.0.0.9:9000/d.xml" to description("TV")))
        s.controlPoint.start()
        runCurrent()
        s.announce(alive("http://10.0.0.9:9000/d.xml"), from = "10.0.0.9")
        runCurrent()
        assertEquals(listOf("TV"), s.names)

        // Announced again: known, so not fetched again.
        s.announce(alive("http://10.0.0.9:9000/d.xml"), from = "10.0.0.9")
        runCurrent()
        assertEquals(1, s.requests.count { it.endsWith("/d.xml") })

        s.announce(byebye, from = "10.0.0.9")
        runCurrent()
        assertEquals(emptyList(), s.names)
    }

    @Test
    fun aRendererThatStopsAnnouncingExpires() = runTest {
        val s = Setup(this, mapOf("http://10.0.0.9:9000/d.xml" to description("TV")))
        s.controlPoint.start()
        runCurrent()
        s.announce(alive("http://10.0.0.9:9000/d.xml", maxAge = 60), from = "10.0.0.9")
        runCurrent()
        assertEquals(listOf("TV"), s.names)

        advanceTimeBy(45_000) // the first purge after 30 s: still fresh
        assertEquals(listOf("TV"), s.names)
        advanceTimeBy(30_000) // past its 60 s
        assertEquals(emptyList(), s.names)
    }

    @Test
    fun searchingAsksTheLanAndARepliedRendererIsFound() = runTest {
        val s = Setup(this, mapOf("http://10.0.0.9:9000/d.xml" to description("TV")))
        s.controlPoint.start()
        runCurrent()
        val search = s.lan.sent.first { it.startsWith("M-SEARCH") }
        assertTrue("HOST: $group:1900" in search, search)

        // The TV answers the search at the port it came from.
        val tv = s.lan.host("10.0.0.9").openUdpSocket(1900, group, "10.0.0.9")
        val asked = tv.receive()!!
        tv.send(
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://10.0.0.9:9000/d.xml\r\nST: ssdp:all\r\nUSN: uuid:tv-1::upnp:rootdevice\r\n\r\n".encodeToByteArray(),
            asked.host, asked.port,
        )
        runCurrent()
        assertEquals(listOf("TV"), s.names)
    }

    @Test
    fun commandsReachARendererWhereItIsNow() = runTest {
        val s = Setup(
            this,
            mapOf(
                "http://10.0.0.9:9000/d.xml" to description("TV"),
                "http://10.0.0.9:9100/d.xml" to description("TV"),
            ),
        )
        val renderers = RendererRepositoryImpl(s.controlPoint, SoapClient(s.http))
        val tv = MediaDevice("uuid:tv-1", "TV", "", "", "10.0.0.9", null, isServer = false, isRenderer = true)
        s.controlPoint.start()
        runCurrent()
        s.announce(alive("http://10.0.0.9:9000/d.xml"), from = "10.0.0.9")
        runCurrent()
        renderers.play(tv)
        assertEquals("http://10.0.0.9:9000/avt/control", s.requests.last())

        // The TV restarted on another port: its next announcement says where.
        s.announce(alive("http://10.0.0.9:9100/d.xml"), from = "10.0.0.9")
        runCurrent()
        renderers.play(tv)
        assertEquals("http://10.0.0.9:9100/avt/control", s.requests.last())
        assertEquals(listOf("TV"), s.names)
    }
}
