@file:OptIn(ExperimentalForeignApi::class)

package com.appdevper.mediaplayer.data.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.INADDR_ANY
import platform.posix.IPPROTO_IP
import platform.posix.IP_ADD_MEMBERSHIP
import platform.posix.IP_MULTICAST_IF
import platform.posix.IP_MULTICAST_TTL
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.bind
import platform.posix.errno
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.in_addr
import platform.darwin.inet_addr
import platform.darwin.inet_ntoa
import platform.posix.ip_mreq
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import kotlin.concurrent.Volatile

/** iOS is little-endian; ports go on the wire big-endian. */
private fun swap16(v: Int): UShort = (((v and 0xff) shl 8) or ((v shr 8) and 0xff)).toUShort()

actual fun localIpv4Address(): String? = memScoped {
    val list = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(list.ptr) != 0) return null
    val found = mutableListOf<Pair<String, String>>()
    try {
        var p = list.value
        while (p != null) {
            val ifa = p.pointed
            val sa = ifa.ifa_addr
            val flags = ifa.ifa_flags.toInt()
            if (sa != null && sa.pointed.sa_family.toInt() == AF_INET &&
                (flags and IFF_UP) != 0 && (flags and IFF_LOOPBACK) == 0
            ) {
                val name = ifa.ifa_name?.toKString().orEmpty()
                val ip = inet_ntoa(sa.reinterpret<sockaddr_in>().pointed.sin_addr.readValue())?.toKString()
                if (ip != null && !ip.startsWith("169.254.")) found += name to ip
            }
            p = ifa.ifa_next
        }
    } finally {
        freeifaddrs(list.value)
    }
    // en0 = Wi‑Fi on iPhone/iPad; pdp_ip = cellular; utun/ipsec = VPN.
    fun score(name: String) = when {
        name == "en0" -> 0
        name.startsWith("en") || name.startsWith("bridge") -> 1
        name.startsWith("pdp_ip") || name.startsWith("utun") || name.startsWith("ipsec") -> 9
        else -> 5
    }
    found.minByOrNull { score(it.first) }?.takeIf { score(it.first) < 9 }?.second
}

private class PosixUdpSocket(private val fd: Int) : UdpSocket {
    @Volatile
    private var closed = false

    override suspend fun receive(): Datagram? = withContext(Dispatchers.IO) {
        check(!closed) { "socket closed" }
        val buf = ByteArray(8 * 1024)
        memScoped {
            val addr = alloc<sockaddr_in>()
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<sockaddr_in>().convert()
            val n = buf.usePinned { pinned ->
                recvfrom(fd, pinned.addressOf(0), buf.size.convert(), 0, addr.ptr.reinterpret(), len.ptr)
            }
            if (n < 0) {
                val e = errno
                check(!closed) { "socket closed" }
                if (e == EAGAIN || e == EWOULDBLOCK || e == EINTR) return@withContext null
                error("recvfrom failed: errno $e")
            }
            val host = inet_ntoa(addr.sin_addr.readValue())?.toKString().orEmpty()
            val port = swap16(addr.sin_port.toInt()).toInt()
            Datagram(buf.copyOf(n.toInt()), host, port)
        }
    }

    override suspend fun send(data: ByteArray, host: String, port: Int) = withContext(Dispatchers.IO) {
        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = swap16(port)
            addr.sin_addr.s_addr = inet_addr(host)
            data.usePinned { pinned ->
                sendto(fd, pinned.addressOf(0), data.size.convert(), 0, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
        }
        Unit
    }

    override fun close() {
        if (closed) return
        closed = true
        platform.posix.close(fd)
    }
}

actual fun openUdpSocket(port: Int, multicastGroup: String?, localIp: String?): UdpSocket {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    check(fd >= 0) { "socket() failed: errno $errno" }
    memScoped {
        val one = alloc<IntVar>()
        one.value = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())

        val tv = alloc<timeval>()
        tv.tv_sec = 1
        tv.tv_usec = 0
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().convert()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = swap16(port)
        addr.sin_addr.s_addr = INADDR_ANY
        if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            val e = errno
            platform.posix.close(fd)
            error("bind($port) failed: errno $e")
        }

        val ttl = alloc<UByteVar>()
        ttl.value = 4u
        setsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL, ttl.ptr, sizeOf<UByteVar>().convert())

        if (localIp != null) {
            val iface = alloc<in_addr>()
            iface.s_addr = inet_addr(localIp)
            setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, iface.ptr, sizeOf<in_addr>().convert())
        }
        if (multicastGroup != null) {
            val mreq = alloc<ip_mreq>()
            mreq.imr_multiaddr.s_addr = inet_addr(multicastGroup)
            mreq.imr_interface.s_addr = localIp?.let { inet_addr(it) } ?: INADDR_ANY
            if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert()) != 0) {
                val e = errno
                platform.posix.close(fd)
                // Without the com.apple.developer.networking.multicast entitlement iOS refuses this.
                error("IP_ADD_MEMBERSHIP failed: errno $e")
            }
        }
    }
    return PosixUdpSocket(fd)
}
