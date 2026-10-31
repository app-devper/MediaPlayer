package app.devper.appplayer.data

import app.devper.appplayer.data.renderer.MediaRenderer
import app.devper.appplayer.data.renderer.RendererConfig
import app.devper.appplayer.data.upnp.DeviceDescription
import app.devper.appplayer.data.upnp.DidlObject
import app.devper.appplayer.data.upnp.DidlResource
import app.devper.appplayer.data.upnp.RendererClient
import app.devper.appplayer.data.upnp.SoapClient
import app.devper.appplayer.data.upnp.SsdpEngine
import app.devper.appplayer.data.upnp.UpnpException
import app.devper.appplayer.data.upnp.UpnpTypes
import app.devper.appplayer.domain.model.CastCommand
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.ReceiverLink
import app.devper.appplayer.domain.model.ReceiverPlayback
import app.devper.appplayer.domain.model.ReceiverStatus
import app.devper.appplayer.domain.model.RendererStatus
import app.devper.appplayer.domain.model.TransportState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Starts the real renderer and drives it with the app's own DLNA controller client. */
class MediaRendererE2ETest {

    @Test
    fun controlStatusAndEvents() = runBlocking {
        // SSDP on an in-memory LAN, HTTP on loopback: no network needed.
        val ip = "127.0.0.1"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient()
        val renderer = MediaRenderer(SsdpEngine(scope, network = InMemoryLan().host(ip)), http, scope)
        val commands = Channel<CastCommand>(Channel.UNLIMITED)
        // The playback session, as far as the renderer sees it: owned from a load until the user plays something else.
        val link = object : ReceiverLink {
            override var owns = false
            override fun send(command: CastCommand): Boolean {
                if (command is CastCommand.Load) owns = true
                else if (!owns && command !is CastCommand.SetVolume && command !is CastCommand.SetMute) return false
                commands.trySend(command)
                return true
            }
        }
        renderer.connect(link)
        val events = Channel<String>(Channel.UNLIMITED)
        val sink = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            routing {
                route("/cb", HttpMethod("NOTIFY")) {
                    handle {
                        events.send(call.receiveText())
                        call.respond(io.ktor.http.HttpStatusCode.OK, "")
                    }
                }
            }
        }.startSuspend(wait = false)
        val sinkPort = sink.engine.resolvedConnectors().first().port

        suspend fun nextCommand() = withTimeout(5_000) { commands.receive() }
        suspend fun eventMatching(text: String) = withTimeout(5_000) {
            var e: String
            do e = events.receive() while (text !in e)
            e
        }

        try {
            renderer.start(RendererConfig("Living Room & Co", "uuid:renderer-test"))
            assertIs<ReceiverStatus.Running>(renderer.state.value)

            val location = assertNotNull(renderer.location)
            val device = DeviceDescription.parse(http.get(location).bodyAsText(), location).single()
            assertEquals("Living Room & Co", device.friendlyName)
            assertTrue(device.isRenderer)
            assertTrue(device.service(UpnpTypes.RENDERING_CONTROL) != null)

            val subscribe = http.request(device.service(UpnpTypes.AV_TRANSPORT)!!.eventSubUrl) {
                method = HttpMethod("SUBSCRIBE")
                header("CALLBACK", "<http://$ip:$sinkPort/cb>")
                header("NT", "upnp:event")
                header("TIMEOUT", "Second-300")
            }
            assertEquals(200, subscribe.status.value)
            assertTrue(subscribe.headers["SID"].orEmpty().startsWith("uuid:"))
            eventMatching("NO_MEDIA_PRESENT") // initial event

            val client = RendererClient(SoapClient(http), device)
            val url = "http://$ip:9/holiday.mp4"
            val clip = DidlObject(
                id = "7", parentId = "1", title = "Holiday", upnpClass = "object.item.videoItem", isContainer = false,
                childCount = null, artist = null, album = null, albumArtUri = null,
                resources = listOf(DidlResource(url, "http-get:*:video/mp4:*", 1_000, "0:01:00.000", null)),
            )
            client.load(clip) // Stop (refused: nothing loaded), SetAVTransportURI, Play

            val load = assertIs<CastCommand.Load>(nextCommand())
            assertEquals(false, load.autoPlay)
            assertEquals("Holiday", load.entry.title)
            assertEquals(MediaKind.VIDEO, load.entry.kind)
            assertEquals(url, load.entry.streamUrl)
            assertEquals(CastCommand.Play, nextCommand()) // not a second Load: the app just hasn't caught up yet
            assertEquals(TransportState.TRANSITIONING.name, client.status().state)

            // The app reports what its player is doing.
            renderer.publish(ReceiverPlayback(load.entry, RendererStatus(TransportState.PLAYING, 5_000, 60_000), volume = 80))
            val status = client.status()
            assertEquals("PLAYING", status.state)
            assertEquals(5_000, status.positionMs)
            assertEquals(60_000, status.durationMs)
            eventMatching("TransportState val=&quot;PLAYING&quot;")

            client.seek(42_000)
            assertEquals(CastCommand.Seek(42_000), nextCommand())
            client.pause()
            assertEquals(CastCommand.Pause, nextCommand())
            assertEquals(80, client.volume())
            client.setVolume(30)
            assertEquals(CastCommand.SetVolume(30), nextCommand())
            assertEquals(30, client.volume())

            val avt = device.service(UpnpTypes.AV_TRANSPORT)!!
            val wrongInstance = assertFailsWith<UpnpException> { SoapClient(http).invoke(avt, "Play", listOf("InstanceID" to "3", "Speed" to "1")) }
            assertEquals(718, wrongInstance.code)
            val unknown = assertFailsWith<UpnpException> { SoapClient(http).invoke(avt, "Record", listOf("InstanceID" to "0")) }
            assertEquals(401, unknown.code)

            // A non-http URI is refused.
            val bad = assertFailsWith<UpnpException> {
                SoapClient(http).invoke(avt, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to "file:///etc/passwd", "CurrentURIMetaData" to ""))
            }
            assertEquals(716, bad.code)

            // The user plays something else here: the cast item is no longer current, Play brings it back.
            link.owns = false
            renderer.publish(ReceiverPlayback(load.entry.copy(streamUrl = "http://other/x.mp4"), RendererStatus(TransportState.PLAYING, 0, 0)))
            assertEquals("STOPPED", client.status().state)
            client.play()
            assertEquals(CastCommand.Load(load.entry, autoPlay = true), nextCommand())
        } finally {
            renderer.stop()
            assertIs<ReceiverStatus.Stopped>(renderer.state.value)
            sink.stop(0, 500)
            http.close()
            scope.cancel()
        }
    }
}
