package app.devper.appplayer.data.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

actual fun localIpv4Address(): String? {
    val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif to it } }
        .filter { (_, a) -> !a.isLoopbackAddress && !a.isLinkLocalAddress }
    // Prefer Wi‑Fi/Ethernet over VPN/tunnel/docker interfaces.
    fun score(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("en") || name.startsWith("eth") || name.startsWith("wl") -> 0
        name.startsWith("ap") || name.startsWith("swlan") -> 1
        name.startsWith("tun") || name.startsWith("utun") || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth") -> 3
        else -> 2
    }
    return candidates.minByOrNull { (nif, a) -> score(nif.name) * 10 + if (a.isSiteLocalAddress) 0 else 1 }?.second?.hostAddress
}

private class JvmUdpSocket(private val socket: MulticastSocket) : UdpSocket {
    private val buffer = ByteArray(8 * 1024)

    override suspend fun receive(): Datagram? = withContext(Dispatchers.IO) {
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
            Datagram(packet.data.copyOf(packet.length), packet.address.hostAddress ?: "", packet.port)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override suspend fun send(data: ByteArray, host: String, port: Int) = withContext(Dispatchers.IO) {
        socket.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
    }

    override fun close() = socket.close()
}

actual fun openUdpSocket(port: Int, multicastGroup: String?, localIp: String?): UdpSocket {
    val socket = MulticastSocket(null as java.net.SocketAddress?)
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(port))
    socket.soTimeout = 1000
    socket.timeToLive = 4
    val nif = localIp?.let { runCatching { NetworkInterface.getByInetAddress(InetAddress.getByName(it)) }.getOrNull() }
    if (nif != null) runCatching { socket.networkInterface = nif }
    if (multicastGroup != null) {
        socket.joinGroup(InetSocketAddress(InetAddress.getByName(multicastGroup), 0), nif)
    }
    return JvmUdpSocket(socket)
}
