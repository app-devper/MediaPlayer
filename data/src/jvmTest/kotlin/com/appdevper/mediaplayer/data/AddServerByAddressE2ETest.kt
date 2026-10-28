package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.platform.LocalMedia
import com.appdevper.mediaplayer.data.platform.MediaLibrary
import com.appdevper.mediaplayer.data.platform.localIpv4Address
import com.appdevper.mediaplayer.data.server.MediaServer
import com.appdevper.mediaplayer.data.server.ServerConfig
import com.appdevper.mediaplayer.data.server.ServerState
import com.appdevper.mediaplayer.data.upnp.ControlPoint
import com.appdevper.mediaplayer.data.upnp.SsdpEngine
import com.appdevper.mediaplayer.domain.model.MediaKind
import io.ktor.client.HttpClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.junit.Assume.assumeNotNull

/** Adding a server by its address (no multicast discovery), against the real DLNA server. */
class AddServerByAddressE2ETest {

    private val library = object : MediaLibrary {
        override suspend fun scan(kinds: Set<MediaKind>) = emptyList<LocalMedia>()
        override fun open(media: LocalMedia, offset: Long) = Buffer()
    }

    @Test
    fun findsTheServerByHostHostAndPortOrDescriptionUrl() = runBlocking {
        val ip = localIpv4Address()
        assumeNotNull(ip) // needs a LAN interface
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient()
        // One SSDP engine for both, as in the app: the server answers on the port the control point uses.
        val ssdp = SsdpEngine(scope)
        val server = MediaServer(ssdp, http, scope)
        try {
            server.start(ServerConfig("Living Room PC", "uuid:by-address-1", setOf(MediaKind.VIDEO)), library)
            val running = assertIs<ServerState.Running>(server.state.value)

            // Generous timeout: a cold CI runner's first HTTP request and JIT can take seconds. Another
            // test's device on this host may answer too, so look for ours among what's found.
            for (address in listOf(ip!!, running.url.removePrefix("http://"), "${running.url}/description.xml")) {
                val controlPoint = ControlPoint(ssdp, http, scope)
                val found = controlPoint.addByAddress(address, timeoutMs = 10_000)
                val ours = found.filter { it.friendlyName == "Living Room PC" }
                assertEquals(1, ours.size, "$address found ${found.map { "${it.friendlyName} @ ${it.location}" }}")
                assertTrue(ours.single().isMediaServer, "$address: ${ours.single().deviceType}")
                assertEquals(listOf(address), controlPoint.addedAddresses.value) // found something: kept
            }

            // Our own device is never offered (e.g. our renderer, while it runs): nothing found, nothing kept.
            val hidingOurs = ControlPoint(ssdp, http, scope, ownUdns = { setOf("uuid:by-address-1") })
            assertEquals(emptyList(), hidingOurs.addByAddress(ip, timeoutMs = 3_000).filter { it.friendlyName == "Living Room PC" })

            // The server answers a unicast M-SEARCH (what an iPhone without multicast relies on for
            // other servers, whose ports aren't known): the reply carries its LOCATION.
            DatagramSocket().use { client ->
                client.soTimeout = 3_000
                val search = "M-SEARCH * HTTP/1.1\r\nHOST: $ip:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n".toByteArray()
                client.send(DatagramPacket(search, search.size, InetAddress.getByName(ip), 1900))
                val reply = DatagramPacket(ByteArray(2048), 2048)
                client.receive(reply)
                val text = String(reply.data, 0, reply.length)
                assertTrue(text.startsWith("HTTP/1.1 200"), "unicast M-SEARCH reply: $text")
                assertTrue("LOCATION: ${running.url}/description.xml" in text, "unicast M-SEARCH reply: $text")
            }

            // Nobody there (TEST-NET-1, never a real host): nothing, within the timeout.
            val started = System.currentTimeMillis()
            val asked = ControlPoint(ssdp, http, scope)
            val nobody = asked.addByAddress("192.0.2.1", timeoutMs = 1_500)
            assertEquals(emptyList(), nobody.map { it.friendlyName }, "192.0.2.1 found something")
            assertEquals(emptyList(), asked.addedAddresses.value) // found nothing: not kept
            val took = System.currentTimeMillis() - started
            assertTrue(took < 6_000, "giving up on 192.0.2.1 took $took ms (timeout 1500)")
        } finally {
            server.stop()
            http.close()
            scope.cancel()
        }
    }
}
