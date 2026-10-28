package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.upnp.DeviceDescription
import com.appdevper.mediaplayer.data.upnp.SoapClient
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.data.upnp.UpnpException
import com.appdevper.mediaplayer.data.upnp.host.ConnectionManager
import com.appdevper.mediaplayer.data.upnp.host.DeviceSpec
import com.appdevper.mediaplayer.data.upnp.host.GenaPublisher
import com.appdevper.mediaplayer.data.upnp.host.LocalDevice
import com.appdevper.mediaplayer.data.upnp.host.LocalService
import com.appdevper.mediaplayer.data.upnp.host.SoapReply
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A device from its spec: described, announced, controlled and evented the same way, on loopback. */
class LocalDeviceTest {

    @Test
    fun aSpecIsDescribedAnnouncedControlledAndEvented() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient()
        val lan = InMemoryLan()
        var counter = 1
        val clock = LocalService(
            type = "urn:example-com:service:Clock:1",
            id = "Clock",
            path = "clock",
            scpd = "<scpd/>",
            events = { GenaPublisher.properties(listOf("Count" to counter.toString())) },
            control = { request ->
                when (request.action) {
                    "Tick" -> SoapReply.Ok(listOf("Count" to (++counter).toString(), "Echo" to request.args["Say"].orEmpty()))
                    else -> SoapReply.invalidAction
                }
            },
        )
        val spec = DeviceSpec(
            "urn:schemas-upnp-org:device:MediaServer:1", "Clock & Co", "uuid:clock-1", "A clock", "DMS-1.50",
            listOf(clock, ConnectionManager.service(source = "http-get:*:video/mp4:*", sink = "")),
        )
        val device = LocalDevice(SsdpEngine(scope, network = lan.host("127.0.0.1")), http, scope, listOf(0))
        val events = Channel<String>(Channel.UNLIMITED)
        val sink = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                route("/cb", HttpMethod("NOTIFY")) {
                    handle {
                        events.send(call.receiveText())
                        call.respond(io.ktor.http.HttpStatusCode.OK, "")
                    }
                }
            }
        }.startSuspend(wait = false)
        try {
            device.start(spec)
            val location = device.location!!

            // Described: every service, at the URLs it's served on.
            val described = DeviceDescription.parse(http.get(location).bodyAsText(), location).single()
            assertEquals("Clock & Co", described.friendlyName)
            assertEquals(listOf("urn:example-com:service:Clock:1", "urn:schemas-upnp-org:service:ConnectionManager:1"), described.services.map { it.serviceType })
            for (s in described.services) assertEquals(200, http.get(s.scpdUrl).status.value, s.scpdUrl)

            // Announced: the device and each service type.
            withTimeout(5_000) { while (lan.sent.count { it.startsWith("NOTIFY") } < 5) kotlinx.coroutines.delay(20) }
            val types = lan.sent.filter { "ssdp:alive" in it }.map { it.lines().first { l -> l.startsWith("NT:") } }.toSet()
            assertEquals(
                setOf("NT: upnp:rootdevice", "NT: uuid:clock-1", "NT: ${spec.type}", "NT: ${clock.type}", "NT: urn:schemas-upnp-org:service:ConnectionManager:1"),
                types,
            )

            // Controlled: answers and faults from the handler.
            val soap = SoapClient(http)
            val service = described.service(clock.type)!!
            assertEquals(mapOf("Count" to "2", "Echo" to "hi & bye"), soap.invoke(service, "Tick", listOf("Say" to "hi & bye")))
            assertEquals("Invalid Action", assertFailsWith<UpnpException> { soap.invoke(service, "Explode") }.message)
            assertEquals("Output", soap.invoke(described.service("urn:schemas-upnp-org:service:ConnectionManager:1")!!, "GetCurrentConnectionInfo")["Direction"])

            // Evented: the subscriber gets the state now, then each change.
            val subscribe = http.request(service.eventSubUrl) {
                method = HttpMethod("SUBSCRIBE")
                header("CALLBACK", "<http://127.0.0.1:${sink.engine.resolvedConnectors().first().port}/cb>")
                header("NT", "upnp:event")
            }
            assertEquals(200, subscribe.status.value)
            assertTrue("<Count>2</Count>" in withTimeout(5_000) { events.receive() })
            counter = 9
            device.notifyChanged("Clock")
            assertTrue("<Count>9</Count>" in withTimeout(5_000) { events.receive() })

            device.stop(byeBye = true)
            assertTrue(lan.sent.any { "ssdp:byebye" in it })
        } finally {
            sink.stop(0, 0)
            device.stop(byeBye = false)
            http.close()
            scope.cancel()
        }
    }
}
