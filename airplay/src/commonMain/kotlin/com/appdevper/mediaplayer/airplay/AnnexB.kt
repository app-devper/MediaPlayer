package com.appdevper.mediaplayer.airplay

/** H.264 Annex-B access units as AirPlay mirroring sends them (start codes, in-band SPS/PPS). */
internal object AnnexB {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** NAL units of [au] without their start codes. */
    fun nalUnits(au: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>() // (start code offset, payload offset)
        var i = 0
        while (i + 2 < au.size) {
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte()) {
                if (au[i + 2] == 1.toByte()) {
                    starts += i to i + 3
                    i += 3
                    continue
                }
                if (i + 3 < au.size && au[i + 2] == 0.toByte() && au[i + 3] == 1.toByte()) {
                    starts += i to i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexed { n, (_, payload) ->
            val end = starts.getOrNull(n + 1)?.first ?: au.size
            au.copyOfRange(payload, end)
        }.filter { it.isNotEmpty() }
    }

    fun type(nal: ByteArray): Int = nal[0].toInt() and 0x1f

    fun withStartCode(nal: ByteArray): ByteArray = byteArrayOf(0, 0, 0, 1) + nal
}

/** AirPlay volume (-30..0 dB, -144 = mute) → linear gain 0..1, on a perceptual curve. */
fun airPlayGain(db: Float): Float {
    if (db <= -30f) return 0f
    if (db >= 0f) return 1f
    val fraction = (db + 30f) / 30f
    return fraction * fraction
}
