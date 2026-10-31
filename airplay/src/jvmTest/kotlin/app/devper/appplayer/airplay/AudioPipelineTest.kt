package app.devper.appplayer.airplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The AirPlay audio pipeline both platforms share, with a fake decoder and output. */
class AudioPipelineTest {
    private val log = mutableListOf<String>()
    private val configs = mutableListOf<List<Byte>>()

    /** Decodes a frame to itself, but only on the next drain (as MediaCodec's output comes later). */
    private inner class LateDecoder(val name: String) : AacDecoder {
        private val pending = ArrayDeque<ByteArray>()
        override fun send(frame: ByteArray, ptsUs: Long) { pending += frame }
        override fun drain(out: (ByteArray, Int, Int) -> Unit) {
            // Held back one call: only what was sent before the last drain comes out.
            while (pending.size > 1) out(pending.removeFirst(), 48_000, 2)
        }
        override fun flush() { pending.clear(); log += "$name:flush" }
        override fun release() { log += "$name:release" }
    }

    private val sink = object : PcmSink {
        override fun write(pcm: ByteArray, rate: Int, channels: Int) { log += "out:${pcm[0]}@$rate/$channels" }
        override fun flush() { log += "out:flush" }
        override fun setVolume(gain: Float) { log += "out:gain=$gain" }
        override fun release() { log += "out:release" }
    }

    private var made = 0
    private val pipeline = AudioPipeline({ config -> configs += config.toList(); LateDecoder("aac${++made}") }, sink)

    @Test
    fun theFormatPicksTheDecoderAndPcmGoesStraightThrough() {
        pipeline.setFormat(AirPlayAudioCodec.PCM)
        pipeline.frame(byteArrayOf(1), 0)
        assertEquals(listOf("out:1@44100/2"), log)
        assertEquals(emptyList(), configs) // no decoder for PCM

        pipeline.setFormat(AirPlayAudioCodec.AAC_ELD)
        assertEquals(listOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00), configs.single())
        pipeline.frame(byteArrayOf(2), 0)
        pipeline.frame(byteArrayOf(3), 0)
        pipeline.drain()
        assertEquals("out:2@48000/2", log.last()) // what the decoder says, not 44.1 kHz

        // Another format: the old decoder goes.
        pipeline.setFormat(AirPlayAudioCodec.AAC_LC)
        assertEquals("aac1:release", log.last())
        assertEquals(listOf<Byte>(0x12, 0x10), configs.last())

        // Unknown: nothing plays.
        pipeline.setFormat(null)
        log.clear()
        pipeline.frame(byteArrayOf(4), 0)
        assertEquals(emptyList(), log)
    }

    @Test
    fun aFlushEmptiesDecoderAndOutputAndReleaseFreesBoth() {
        pipeline.setFormat(AirPlayAudioCodec.AAC_LC)
        pipeline.frame(byteArrayOf(1), 0)
        pipeline.flush()
        pipeline.frame(byteArrayOf(2), 0)
        pipeline.frame(byteArrayOf(3), 0)
        // The frame sent before the flush never plays.
        assertEquals(listOf("aac1:flush", "out:flush", "out:2@48000/2"), log)
        pipeline.setVolume(0.5f)
        pipeline.release()
        assertEquals(listOf("out:gain=0.5", "aac1:release", "out:release"), log.takeLast(3))
        assertNull(AudioPipeline.aacConfig(AirPlayAudioCodec.PCM))
    }
}
