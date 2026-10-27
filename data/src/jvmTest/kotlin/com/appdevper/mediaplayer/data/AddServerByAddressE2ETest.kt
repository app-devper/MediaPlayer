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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import org.junit.Assume.assumeNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        val server = MediaServer(ssdp, scope)
        try {
            server.start(ServerConfig("Living Room PC", "uuid:by-address-1", setOf(MediaKind.VIDEO)), library)
            val running = assertIs<ServerState.Running>(server.state.value)

            for (address in listOf(ip!!, running.url.removePrefix("http://"), "${running.url}/description.xml")) {
                val found = ControlPoint(ssdp, http, scope).addByAddress(address)
                assertEquals(listOf("Living Room PC"), found.map { it.friendlyName }, address)
                assertTrue(found.single().isMediaServer)
            }

            // Nobody there (TEST-NET-1, never a real host): nothing, within the timeout.
            val started = System.currentTimeMillis()
            assertEquals(emptyList(), ControlPoint(ssdp, http, scope).addByAddress("192.0.2.1", timeoutMs = 1_500))
            assertTrue(System.currentTimeMillis() - started < 4_000)
        } finally {
            server.stop()
            http.close()
            scope.cancel()
        }
    }
}
