package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.platform.Datagram
import com.appdevper.mediaplayer.data.platform.Network
import com.appdevper.mediaplayer.data.platform.UdpSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * A LAN in memory: each [host] is a [Network] at its own address. A datagram to a multicast group
 * reaches every socket in that group on that port (the sender's host too, as multicast loops back);
 * any other reaches the socket bound to that address and port.
 */
class InMemoryLan {
    // State flows only for their atomic updates: tests on several threads share a LAN.
    private val sockets = MutableStateFlow<List<Socket>>(emptyList())
    private val nextPort = MutableStateFlow(40_000)
    private val _sent = MutableStateFlow<List<String>>(emptyList())

    /** Datagrams sent so far, as text, newest last. */
    val sent: List<String> get() = _sent.value

    fun host(ip: String?): Network = object : Network {
        override fun localIpv4Address(): String? = ip
        override fun openUdpSocket(port: Int, multicastGroup: String?, localIp: String?): UdpSocket =
            Socket(localIp ?: ip ?: error("offline"), if (port == 0) nextPort.getAndUpdate { it + 1 } else port, multicastGroup)
                .also { s -> sockets.update { it + s } }
    }

    /** Sends [text] from [fromIp]:[fromPort] as another device on the LAN would. */
    fun send(text: String, fromIp: String, toHost: String, toPort: Int, fromPort: Int = 1900) =
        deliver(Datagram(text.encodeToByteArray(), fromIp, fromPort), toHost, toPort)

    private fun deliver(d: Datagram, host: String, port: Int) {
        _sent.update { it + d.data.decodeToString() }
        sockets.value.filter { it.port == port && (it.group == host || it.ip == host) }
            .forEach { it.inbox.trySend(d) }
    }

    private inner class Socket(val ip: String, val port: Int, val group: String?) : UdpSocket {
        val inbox = Channel<Datagram>(Channel.UNLIMITED)

        override suspend fun receive(): Datagram = inbox.receive()

        override suspend fun send(data: ByteArray, host: String, port: Int) = deliver(Datagram(data, ip, this.port), host, port)

        override fun close() {
            inbox.close()
            sockets.update { it - this }
        }
    }
}
