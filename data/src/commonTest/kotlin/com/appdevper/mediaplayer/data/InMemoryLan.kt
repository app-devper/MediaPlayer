package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.platform.Datagram
import com.appdevper.mediaplayer.data.platform.Network
import com.appdevper.mediaplayer.data.platform.UdpSocket
import kotlinx.coroutines.channels.Channel

/**
 * A LAN in memory: each [host] is a [Network] at its own address. A datagram to a multicast group
 * reaches every socket in that group on that port (the sender's host too, as multicast loops back);
 * any other reaches the socket bound to that address and port.
 */
class InMemoryLan {
    private val sockets = mutableListOf<Socket>()
    private var nextPort = 40_000

    /** Datagrams sent so far, as text, newest last. */
    val sent = mutableListOf<String>()

    fun host(ip: String?): Network = object : Network {
        override fun localIpv4Address(): String? = ip
        override fun openUdpSocket(port: Int, multicastGroup: String?, localIp: String?): UdpSocket =
            Socket(localIp ?: ip ?: error("offline"), if (port == 0) nextPort++ else port, multicastGroup).also { sockets += it }
    }

    /** Sends [text] from [fromIp]:[fromPort] as another device on the LAN would. */
    fun send(text: String, fromIp: String, toHost: String, toPort: Int, fromPort: Int = 1900) =
        deliver(Datagram(text.encodeToByteArray(), fromIp, fromPort), toHost, toPort)

    private fun deliver(d: Datagram, host: String, port: Int) {
        sent += d.data.decodeToString()
        sockets.filter { it.port == port && (it.group == host || it.ip == host) }
            .forEach { it.inbox.trySend(d) }
    }

    private inner class Socket(val ip: String, val port: Int, val group: String?) : UdpSocket {
        val inbox = Channel<Datagram>(Channel.UNLIMITED)

        override suspend fun receive(): Datagram = inbox.receive()

        override suspend fun send(data: ByteArray, host: String, port: Int) = deliver(Datagram(data, ip, this.port), host, port)

        override fun close() {
            inbox.close()
            sockets -= this
        }
    }
}
