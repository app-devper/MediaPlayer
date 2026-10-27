package com.appdevper.mediaplayer.airplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MdnsTest {
    private val name = Mdns.dacpServiceName("C674262D939888D7")

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun labels(vararg parts: String) =
        parts.fold(ByteArray(0)) { acc, l -> acc + byteArrayOf(l.length.toByte()) + l.encodeToByteArray() } + byteArrayOf(0)
    private fun header(qd: Int, an: Int, ns: Int = 0, ar: Int = 0) =
        u16(0) + u16(0x8400) + u16(qd) + u16(an) + u16(ns) + u16(ar)
    private fun srv(owner: ByteArray, port: Int, target: ByteArray = labels("Wowit-iPhone", "local")) =
        owner + u16(33) + u16(0x8001) + byteArrayOf(0, 0, 0x11, 0x94.toByte()) + u16(6 + target.size) +
            u16(0) + u16(0) + u16(port) + target

    @Test
    fun queryAsksForTheSrvRecordWithAUnicastReply() {
        val q = Mdns.srvQuery(name)
        assertEquals(1, ((q[4].toInt() and 0xff) shl 8) or (q[5].toInt() and 0xff)) // one question
        val question = labels("iTunes_Ctrl_C674262D939888D7", "_dacp", "_tcp", "local") + u16(33) + u16(0x8001)
        assertEquals(question.toList(), q.copyOfRange(12, q.size).toList())
    }

    @Test
    fun readsThePortOfTheMatchingSrvRecord() {
        val owner = labels("iTunes_Ctrl_C674262D939888D7", "_dacp", "_tcp", "local")
        val packet = header(qd = 0, an = 1) + srv(owner, 49203)
        assertEquals(49203, Mdns.srvPort(packet, name))
        // Names compare case-insensitively (DNS), the DACP-ID may come in either case.
        assertEquals(49203, Mdns.srvPort(packet, name.lowercase()))
    }

    @Test
    fun followsNameCompressionAndSkipsOtherRecords() {
        // Question echoed, then an A record for another name, then the SRV whose name points at the question.
        val question = labels("iTunes_Ctrl_C674262D939888D7", "_dacp", "_tcp", "local") + u16(33) + u16(1)
        val a = labels("printer", "local") + u16(1) + u16(1) + byteArrayOf(0, 0, 0, 120) + u16(4) + byteArrayOf(192.toByte(), 168.toByte(), 1, 9)
        val pointerToQuestionName = byteArrayOf(0xc0.toByte(), 12)
        val packet = header(qd = 1, an = 1, ar = 1) + question + a + srv(pointerToQuestionName, 7000)
        assertEquals(7000, Mdns.srvPort(packet, name))
    }

    @Test
    fun aPortZeroRecordIsSkippedForTheNextOne() {
        val owner = labels("iTunes_Ctrl_C674262D939888D7", "_dacp", "_tcp", "local")
        assertEquals(49203, Mdns.srvPort(header(qd = 0, an = 2) + srv(owner, 0) + srv(owner, 49203), name))
    }

    @Test
    fun anotherServiceIsNotTaken() {
        val other = labels("iTunes_Ctrl_FFFFFFFFFFFFFFFF", "_dacp", "_tcp", "local")
        assertNull(Mdns.srvPort(header(qd = 0, an = 1) + srv(other, 1234), name))
    }

    @Test
    fun malformedPacketsGiveNull() {
        val owner = labels("iTunes_Ctrl_C674262D939888D7", "_dacp", "_tcp", "local")
        val good = header(qd = 0, an = 1) + srv(owner, 49203)
        // Every truncation of a valid reply: no crash, no port.
        for (n in 0 until good.size) assertNull(Mdns.srvPort(good.copyOf(n), name), "truncated to $n")
        // A compression pointer to itself (loop) and one past the end.
        val loop = header(qd = 0, an = 1) + byteArrayOf(0xc0.toByte(), 12) + u16(33) + u16(1) + ByteArray(4) + u16(6) + ByteArray(6)
        assertNull(Mdns.srvPort(loop, name))
        val outside = header(qd = 0, an = 1) + byteArrayOf(0xc0.toByte(), 0xff.toByte()) + u16(33) + u16(1) + ByteArray(4) + u16(6) + ByteArray(6)
        assertNull(Mdns.srvPort(outside, name))
        // Record counts far larger than the packet.
        assertNull(Mdns.srvPort(u16(0) + u16(0x8400) + u16(0) + u16(0xffff) + u16(0) + u16(0), name))
        // Reserved label type (0x40) and a record length past the end.
        assertNull(Mdns.srvPort(header(qd = 0, an = 1) + byteArrayOf(0x41) + ByteArray(20), name))
        assertNull(Mdns.srvPort(header(qd = 0, an = 1) + owner + u16(33) + u16(1) + ByteArray(4) + u16(500) + ByteArray(6), name))
        // Port 0 isn't a port.
        assertNull(Mdns.srvPort(header(qd = 0, an = 1) + srv(owner, 0), name))
    }
}
