package app.devper.appplayer.data.upnp.host

import app.devper.appplayer.data.platform.platformName
import app.devper.appplayer.data.upnp.SsdpEngine
import app.devper.appplayer.data.upnp.SsdpMessage
import app.devper.appplayer.data.upnp.UpnpTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Announces one local UPnP root device over SSDP and answers M-SEARCH for it. */
internal class SsdpAdvertiser(
    private val ssdp: SsdpEngine,
    private val scope: CoroutineScope,
    private val udn: String,
    /** The device type followed by its service types. */
    private val types: List<String>,
    private val location: String,
) {
    private val jobs = mutableListOf<Job>()

    private val notificationTypes: List<Pair<String, String>> =
        listOf("upnp:rootdevice" to "$udn::upnp:rootdevice", udn to udn) + types.map { it to "$udn::$it" }

    fun start() {
        jobs += scope.launch { ssdp.messages.collect { if (it.isSearch) answerSearch(it) } }
        jobs += scope.launch {
            // Announce a few times, then refresh well before max-age expires.
            repeat(3) { announce(alive = true); delay(1_000) }
            while (isActive) {
                delay(ANNOUNCE_INTERVAL_MS)
                announce(alive = true)
            }
        }
    }

    suspend fun stop(byeBye: Boolean) {
        if (byeBye) runCatching { announce(alive = false) }
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun announce(alive: Boolean) {
        for ((nt, usn) in notificationTypes) {
            val headers = buildList {
                add("HOST" to "${SsdpEngine.SSDP_ADDRESS}:${SsdpEngine.SSDP_PORT}")
                if (alive) {
                    add("CACHE-CONTROL" to "max-age=$MAX_AGE")
                    add("LOCATION" to location)
                    add("SERVER" to SERVER_HEADER)
                }
                add("NT" to nt)
                add("NTS" to if (alive) "ssdp:alive" else "ssdp:byebye")
                add("USN" to usn)
            }
            ssdp.sendMulticast(SsdpMessage.build("NOTIFY * HTTP/1.1", headers))
        }
    }

    private fun answerSearch(msg: SsdpMessage) {
        val st = msg.header("ST") ?: return
        val matches = notificationTypes.filter { (nt, _) ->
            st == "ssdp:all" || st == nt || (nt.startsWith("urn:") && UpnpTypes.sameType(st, nt))
        }
        if (matches.isEmpty()) return
        val mx = msg.header("MX")?.toIntOrNull()?.coerceIn(1, 5) ?: 1
        scope.launch {
            delay(Random.nextLong(0, mx * 300L))
            for ((nt, usn) in matches) {
                val headers = listOf(
                    "CACHE-CONTROL" to "max-age=$MAX_AGE",
                    "EXT" to "",
                    "LOCATION" to location,
                    "SERVER" to SERVER_HEADER,
                    "ST" to if (st == "ssdp:all") nt else st,
                    "USN" to usn,
                )
                ssdp.sendTo(SsdpMessage.build("HTTP/1.1 200 OK", headers), msg.host, msg.port)
            }
        }
    }

    companion object {
        private const val MAX_AGE = 1800
        private const val ANNOUNCE_INTERVAL_MS = 600_000L
        val SERVER_HEADER get() = "$platformName UPnP/1.0 DLNADOC/1.50 MediaPlayerKmp/2.0"
    }
}
