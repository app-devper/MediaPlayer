package app.devper.appplayer.airplay

import kotlin.test.Test
import kotlin.test.assertEquals

class DmapTest {
    private fun item(tag: String, value: ByteArray): ByteArray {
        val n = value.size
        return tag.encodeToByteArray() + byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + value
    }

    @Test
    fun readsTitleArtistAlbumInsideAListingItem() {
        val inner = item("asal", "Kraam".encodeToByteArray()) +
            item("asar", "Bodyslam".encodeToByteArray()) +
            item("astm", byteArrayOf(0, 3, 0x0d, 0x40)) + // song time: ignored
            item("minm", "ขอนแก่น Blues".encodeToByteArray())
        val dmap = item("mlit", inner)
        assertEquals(AirPlayTrackInfo("ขอนแก่น Blues", "Bodyslam", "Kraam"), Dmap.track(dmap))
    }

    @Test
    fun truncatedOrJunkDataIsSafe() {
        val good = item("minm", "Song".encodeToByteArray())
        assertEquals(AirPlayTrackInfo("Song", null, null), Dmap.track(good + byteArrayOf(0x61, 0x73, 0x61, 0x72, 0x7f, 0, 0, 0, 1)))
        assertEquals(AirPlayTrackInfo(null, null, null), Dmap.track(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun progressFromRtpTimestampsAlsoAcrossWraparound() {
        assertEquals(10_000L to 60_000L, Dmap.progress(1_000, 1_000 + 441_000, 1_000 + 2_646_000))
        val start = 0xffff_0000L
        assertEquals(1_000L to 2_000L, Dmap.progress(start, (start + 44_100) and 0xffffffffL, (start + 88_200) and 0xffffffffL))
    }
}
