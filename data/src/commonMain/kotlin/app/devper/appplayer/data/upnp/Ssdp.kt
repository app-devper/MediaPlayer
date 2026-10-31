package app.devper.appplayer.data.upnp

import app.devper.appplayer.data.platform.MulticastLock
import app.devper.appplayer.data.platform.Network
import app.devper.appplayer.data.platform.UdpSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SsdpMessage(
    val startLine: String,
    private val headers: Map<String, String>,
    val host: String,
    val port: Int,
) {
    val isSearch: Boolean get() = startLine.startsWith("M-SEARCH", ignoreCase = true)
    val isNotify: Boolean get() = startLine.startsWith("NOTIFY", ignoreCase = true)
    val isResponse: Boolean get() = startLine.startsWith("HTTP/", ignoreCase = true)

    fun header(name: String): String? = headers[name.uppercase()]

    /** `max-age` from CACHE-CONTROL, defaulting to 1800s. */
    val maxAgeSeconds: Int
        get() = header("CACHE-CONTROL")
            ?.substringAfter("max-age", "")
            ?.trimStart(' ', '=')
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull() ?: 1800

    /** `uuid:xxxx` part of the USN header. */
    val udn: String? get() = header("USN")?.substringBefore("::")?.takeIf { it.startsWith("uuid:") }

    companion object {
        fun parse(data: ByteArray, host: String, port: Int): SsdpMessage? {
            val text = data.decodeToString()
            val lines = text.split("\r\n", "\n")
            val start = lines.firstOrNull()?.trim().orEmpty()
            if (start.isEmpty()) return null
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
            return SsdpMessage(start, headers, host, port)
        }

        fun build(startLine: String, headers: List<Pair<String, String>>): ByteArray = buildString {
            append(startLine).append("\r\n")
            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
            append("\r\n")
        }.encodeToByteArray()
    }
}

/**
 * Owns the two SSDP sockets:
 *  - a multicast listener on 239.255.255.250:1900 (NOTIFY announcements + M-SEARCH from others)
 *  - a unicast socket on an ephemeral port used to send M-SEARCH/NOTIFY and receive search replies.
 * All received messages are published on [messages]; the control point and the media server both
 * consume them.
 */
class SsdpEngine(
    private val scope: CoroutineScope,
    private val multicastLock: MulticastLock = MulticastLock.None,
    private val network: Network = Network.Platform,
) {

    private val _messages = MutableSharedFlow<SsdpMessage>(extraBufferCapacity = 512)
    val messages: SharedFlow<SsdpMessage> = _messages.asSharedFlow()

    private val mutex = Mutex()
    private var listenSocket: UdpSocket? = null
    private var unicastSocket: UdpSocket? = null
    private val readers = mutableListOf<Job>()

    var localIp: String? = null
        private set

    val isRunning: Boolean get() = unicastSocket != null

    /** Starts (or restarts when the LAN address changed). Returns false when there is no network. */
    suspend fun ensureStarted(): Boolean = mutex.withLock {
        val ip = network.localIpv4Address()
        if (unicastSocket != null && ip == localIp) return@withLock true
        stopLocked()
        if (ip == null) return@withLock false
        localIp = ip
        multicastLock.acquire()
        unicastSocket = runCatching { network.openUdpSocket(0, null, ip) }.getOrNull()
        listenSocket = runCatching { network.openUdpSocket(SSDP_PORT, SSDP_ADDRESS, ip) }.getOrNull()
        listOfNotNull(unicastSocket, listenSocket).forEach { sock -> readers += scope.launch { readLoop(sock) } }
        unicastSocket != null
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        listenSocket?.close()
        unicastSocket?.close()
        listenSocket = null
        unicastSocket = null
        readers.forEach { it.cancelAndJoin() }
        readers.clear()
        if (localIp != null) multicastLock.release()
        localIp = null
    }

    private suspend fun readLoop(socket: UdpSocket) {
        while (scope.isActive) {
            val d = try {
                socket.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                break // socket closed
            } ?: continue
            SsdpMessage.parse(d.data, d.host, d.port)?.let { _messages.emit(it) }
        }
    }

    suspend fun sendMulticast(data: ByteArray) {
        val sock = unicastSocket ?: return
        runCatching { sock.send(data, SSDP_ADDRESS, SSDP_PORT) }
    }

    suspend fun sendTo(data: ByteArray, host: String, port: Int) {
        val sock = unicastSocket ?: return
        runCatching { sock.send(data, host, port) }
    }

    /** Sends M-SEARCH a few times (UDP is lossy). */
    suspend fun search(searchTarget: String = "ssdp:all", mx: Int = 2) {
        val msg = SsdpMessage.build(
            "M-SEARCH * HTTP/1.1",
            listOf(
                "HOST" to "$SSDP_ADDRESS:$SSDP_PORT",
                "MAN" to "\"ssdp:discover\"",
                "MX" to mx.toString(),
                "ST" to searchTarget,
                "USER-AGENT" to USER_AGENT,
            ),
        )
        repeat(3) {
            sendMulticast(msg)
            delay(300)
        }
    }

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val USER_AGENT = "MediaPlayerKmp/2.0 UPnP/1.0 DLNADOC/1.50"
    }
}
