package com.appdevper.mediaplayer.airplay

/** Song details an AirPlay audio sender reports. */
data class AirPlayTrackInfo(val title: String?, val artist: String?, val album: String?)

/**
 * DMAP (the DAAP tag format AirPlay audio metadata uses): 4-byte tag, 4-byte big-endian length,
 * payload; container tags (e.g. "mlit") hold more items.
 */
internal object Dmap {
    private val CONTAINERS = setOf("mlit", "mlcl", "cmst", "mdcl")

    fun track(data: ByteArray): AirPlayTrackInfo {
        val strings = HashMap<String, String>()
        collect(data, 0, data.size, strings, depth = 0)
        return AirPlayTrackInfo(strings["minm"], strings["asar"], strings["asal"])
    }

    private fun collect(data: ByteArray, from: Int, to: Int, out: MutableMap<String, String>, depth: Int) {
        var i = from
        while (i + 8 <= to) {
            val tag = data.decodeToString(i, i + 4)
            val len = ((data[i + 4].toInt() and 0xff) shl 24) or ((data[i + 5].toInt() and 0xff) shl 16) or
                ((data[i + 6].toInt() and 0xff) shl 8) or (data[i + 7].toInt() and 0xff)
            val start = i + 8
            if (len < 0 || start + len > to) return // truncated or garbage: keep what we have
            if (tag in CONTAINERS && depth < 4) {
                collect(data, start, start + len, out, depth + 1)
            } else if (tag in setOf("minm", "asar", "asal")) {
                out.getOrPut(tag) { data.decodeToString(start, start + len).trim() }
            }
            i = start + len
        }
    }

    /** RTP timestamps (44.1 kHz, wrapping at 2^32) → (position, duration) in ms. */
    fun progress(start: Long, current: Long, end: Long): Pair<Long, Long> {
        fun span(a: Long, b: Long) = ((b - a) and 0xffffffffL) * 1000 / 44_100
        val duration = span(start, end)
        return span(start, current).coerceAtMost(duration) to duration
    }
}
