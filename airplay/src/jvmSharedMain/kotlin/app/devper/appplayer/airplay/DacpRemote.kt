package app.devper.appplayer.airplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Remote control of the sender (DACP, as the Apple TV remote does): the sender advertises
 * `iTunes_Ctrl_<dacpId>._dacp._tcp` and takes `GET /ctrl-int/1/<command>` with its Active-Remote id.
 *
 * Only ever talks to [sender] (the address that connected to us): an mDNS reply only gives the port,
 * so another host on the network can't redirect the request elsewhere.
 */
internal class DacpRemote(
    private val dacpId: String,
    private val activeRemote: String,
    private val sender: InetAddress,
    private val mdnsPort: Int = 5353,
    /** Also ask the multicast group (some senders don't answer a direct question). Off in tests. */
    private val multicast: Boolean = true,
    private val timeoutMs: Int = 1_500,
) {
    @Volatile private var port: Int? = null
    // One command at a time, in the order sent (and one port lookup for them all).
    private val lock = Mutex()

    enum class Result { Done, Refused, Unreachable }

    suspend fun send(command: AirPlayRemoteCommand): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            // A cached port can go stale (the sender's app restarted): find it again once. Only when
            // we couldn't connect: a request that went out is never sent twice (Next would skip two).
            repeat(2) { attempt ->
                val p = port ?: resolvePort() ?: return@withContext Result.Unreachable
                port = p
                when (val r = request(p, command)) {
                    Result.Unreachable -> if (attempt == 0) port = null
                    else -> return@withContext r
                }
            }
            Result.Unreachable
        }
    }

    /** The DACP port from the sender's mDNS, asked directly (and on the multicast group). */
    private fun resolvePort(): Int? {
        val name = Mdns.dacpServiceName(dacpId)
        val query = Mdns.srvQuery(name)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val targets = buildList {
                add(sender)
                if (multicast) multicastGroup()?.let(::add)
            }
            targets.forEach { runCatching { socket.send(DatagramPacket(query, query.size, it, mdnsPort)) } }
            val buffer = ByteArray(9000)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                // The whole lookup is bounded: answers from other hosts don't restart the wait.
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    return null
                } catch (_: IOException) {
                    return null
                }
                // Only the sender's own answer counts.
                if (!sameHost(packet.address, sender)) continue
                Mdns.srvPort(packet.data.copyOf(packet.length), name)?.let { return it }
            }
        }
    }

    private fun multicastGroup(): InetAddress? = when (sender) {
        is Inet4Address -> InetAddress.getByName("224.0.0.251")
        is Inet6Address -> Inet6Address.getByAddress(null, InetAddress.getByName("ff02::fb").address, sender.scopeId)
        else -> null
    }

    /** Unreachable only when the connection couldn't be made, i.e. nothing was sent. */
    private fun request(port: Int, command: AirPlayRemoteCommand): Result = Socket().use { s ->
        try {
            s.connect(InetSocketAddress(sender, port), timeoutMs)
        } catch (_: IOException) {
            return Result.Unreachable
        }
        try {
            s.soTimeout = timeoutMs
            val host = if (sender is Inet6Address) "[${sender.hostAddress?.substringBefore('%')}]" else sender.hostAddress
            val request = "GET /ctrl-int/1/${command.path} HTTP/1.1\r\n" +
                "Host: $host:$port\r\nActive-Remote: $activeRemote\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(request.encodeToByteArray()); flush() }
            if (statusCode(s.getInputStream().readLine(200)) in 200..299) Result.Done else Result.Refused
        } catch (_: IOException) {
            // Sent, but no (complete) answer: it may well have acted on it, so don't send it again.
            Result.Refused
        }
    }

    private fun java.io.InputStream.readLine(max: Int): String {
        val sb = StringBuilder()
        while (sb.length < max) {
            val b = read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun statusCode(line: String): Int? = line.split(' ').getOrNull(1)?.toIntOrNull()

    private fun sameHost(a: InetAddress, b: InetAddress) = a.address.contentEquals(b.address)
}
