package com.appdevper.mediaplayer.airplay

/**
 * Just enough multicast DNS to find a sender's DACP (remote control) port: one SRV question, and the
 * port from the matching SRV answer. Replies come from the network, so every read is bounds-checked
 * and malformed packets give null.
 */
internal object Mdns {
    private const val TYPE_SRV = 33
    private const val CLASS_IN = 1
    /** Ask for a unicast reply (RFC 6762 §5.4), so we don't need port 5353 ourselves. */
    private const val UNICAST_RESPONSE = 0x8000
    private const val MAX_NAME = 255
    private const val MAX_JUMPS = 16

    /** `iTunes_Ctrl_<DACP-ID>._dacp._tcp.local` */
    fun dacpServiceName(dacpId: String) = "iTunes_Ctrl_$dacpId._dacp._tcp.local"

    fun srvQuery(name: String): ByteArray {
        val labels = name.split('.').filter { it.isNotEmpty() }
        val out = ArrayList<Byte>()
        // Header: id 0, flags 0 (standard query), 1 question, no records.
        repeat(4) { out += 0 }
        out += 0; out += 1
        repeat(6) { out += 0 }
        for (label in labels) {
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63) { "bad label in $name" }
            out += bytes.size.toByte()
            bytes.forEach { out += it }
        }
        out += 0
        out += (TYPE_SRV shr 8).toByte(); out += TYPE_SRV.toByte()
        val qclass = CLASS_IN or UNICAST_RESPONSE
        out += (qclass shr 8).toByte(); out += qclass.toByte()
        return out.toByteArray()
    }

    /** The port of the SRV record named [name] (case-insensitive) anywhere in [packet], or null. */
    fun srvPort(packet: ByteArray, name: String): Int? {
        val r = Reader(packet)
        if (packet.size < 12) return null
        val questions = r.u16(4) ?: return null
        val records = listOf(6, 8, 10).sumOf { r.u16(it) ?: return null }
        var pos = 12
        repeat(questions) {
            pos = r.skipName(pos) ?: return null
            pos += 4
            if (pos > packet.size) return null
        }
        repeat(records) {
            val (recordName, afterName) = r.name(pos) ?: return null
            val type = r.u16(afterName) ?: return null
            val length = r.u16(afterName + 8) ?: return null
            val data = afterName + 10
            if (data + length > packet.size) return null
            if (type == TYPE_SRV && length >= 6 && recordName.equals(name, ignoreCase = true)) {
                // Port 0 (e.g. a goodbye record) isn't usable: keep looking.
                r.u16(data + 4)?.takeIf { it > 0 }?.let { return it }
            }
            pos = data + length
        }
        return null
    }

    private class Reader(private val p: ByteArray) {
        fun u8(at: Int): Int? = if (at in p.indices) p[at].toInt() and 0xff else null
        fun u16(at: Int): Int? {
            val hi = u8(at) ?: return null
            val lo = u8(at + 1) ?: return null
            return (hi shl 8) or lo
        }

        /** Position after the name at [at] (without following compression pointers). */
        fun skipName(at: Int): Int? {
            var i = at
            while (true) {
                val len = u8(i) ?: return null
                when {
                    len == 0 -> return i + 1
                    len and 0xc0 == 0xc0 -> return if (u8(i + 1) != null) i + 2 else null
                    len and 0xc0 != 0 -> return null
                    else -> i += 1 + len
                }
            }
        }

        /** The dotted name at [at] (following compression pointers) and the position after it. */
        fun name(at: Int): Pair<String, Int>? {
            val labels = StringBuilder()
            var i = at
            var end = -1
            var jumps = 0
            while (true) {
                val len = u8(i) ?: return null
                when {
                    len == 0 -> {
                        if (end < 0) end = i + 1
                        return labels.toString() to end
                    }
                    len and 0xc0 == 0xc0 -> {
                        val target = ((len and 0x3f) shl 8) or (u8(i + 1) ?: return null)
                        if (end < 0) end = i + 2
                        if (++jumps > MAX_JUMPS || target >= p.size) return null
                        i = target
                    }
                    len and 0xc0 != 0 -> return null
                    else -> {
                        if (i + 1 + len > p.size) return null
                        if (labels.isNotEmpty()) labels.append('.')
                        labels.append(p.decodeToString(i + 1, i + 1 + len))
                        if (labels.length > MAX_NAME) return null
                        i += 1 + len
                    }
                }
            }
        }
    }
}
