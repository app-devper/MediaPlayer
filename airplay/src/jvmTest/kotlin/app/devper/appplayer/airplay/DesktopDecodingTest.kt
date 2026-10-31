package app.devper.appplayer.airplay

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.javacpp.FloatPointer
import org.junit.Assume.assumeNotNull
import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop decoders turn real H.264 / AAC (encoded here with FFmpeg) into pictures and sound. */
class DesktopDecodingTest {

    @Test
    fun mirroringVideoBecomesPictures() = runBlocking {
        val aus = encodeH264(width = 320, height = 240, frames = 10) // pure red
        val player = AirPlayPlayer()
        try {
            player.onVideoFormat(false, 320, 240)
            aus.forEachIndexed { i, au -> player.onVideo(au, i * 33_000_000L) }
            val frame = withTimeout(10_000) { player.frames.filterNotNull().first() }
            assertEquals(320, frame.width)
            assertEquals(240, frame.height)
            assertEquals(VideoSize(320, 240), player.videoSize.value)
            val center = (120 * 320 + 160) * 4
            val (b, g, r) = Triple(frame.bgra[center].toInt() and 0xff, frame.bgra[center + 1].toInt() and 0xff, frame.bgra[center + 2].toInt() and 0xff)
            assertTrue(r > 200 && g < 60 && b < 60, "expected red, got r=$r g=$g b=$b")

            player.onVideoStop()
            assertEquals(null, player.videoSize.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun aacLcBecomesTheSameTone() {
        val frames = encodeAacLc(seconds = 1, hz = 440.0)
        val pcm = Collections.synchronizedList(mutableListOf<ByteArray>())
        val out = jvmAudioOutput(pcmTap = { pcm += it })
        try {
            out.setFormat(AirPlayAudioCodec.AAC_LC)
            frames.forEach { out.play(it, 0) }
            val deadline = System.currentTimeMillis() + 10_000
            while (pcm.sumOf { it.size } < 44_100 * 4 / 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val bytes = synchronized(pcm) { pcm.reduce { a, b -> a + b } }
            val left = ShortArray(bytes.size / 4) { i -> ((bytes[i * 4 + 1].toInt() shl 8) or (bytes[i * 4].toInt() and 0xff)).toShort() }
            assertTrue(left.size > 20_000, "decoded ${left.size} samples")
            val window = left.copyOfRange(4_096, 4_096 + 11_025) // 0.25 s past the encoder's priming
            assertTrue(window.maxOf { abs(it.toInt()) } > 5_000, "tone is audible")
            val crossings = (1 until window.size).count { (window[it - 1] < 0) != (window[it] < 0) }
            val hz = crossings / 2.0 / 0.25
            assertTrue(hz in 420.0..460.0, "frequency $hz Hz")
        } finally {
            out.release()
        }
    }

    // region encoders (test data)

    private fun encodeH264(width: Int, height: Int, frames: Int): List<ByteArray> {
        val codec = avcodec_find_encoder(AV_CODEC_ID_H264)
        assumeNotNull(codec)
        val c: AVCodecContext = avcodec_alloc_context3(codec)
        c.width(width); c.height(height); c.pix_fmt(AV_PIX_FMT_YUV420P)
        c.time_base(AVRational().num(1).den(30)); c.framerate(AVRational().num(30).den(1))
        c.gop_size(10); c.bit_rate(500_000)
        check(avcodec_open2(c, codec, null as AVDictionary?) >= 0)
        val frame: AVFrame = av_frame_alloc()
        frame.format(AV_PIX_FMT_YUV420P); frame.width(width); frame.height(height)
        check(av_frame_get_buffer(frame, 0) >= 0)
        // Red in BT.601 limited range: Y 81, U 90, V 240
        for (plane in 0..2) {
            val h = if (plane == 0) height else height / 2
            val value = byteArrayOf(81, 90, 240.toByte())[plane]
            val bytes = ByteArray(frame.linesize(plane) * h) { value }
            frame.data(plane).put(bytes, 0, bytes.size)
        }
        val pkt = av_packet_alloc()
        val out = mutableListOf<ByteArray>()
        fun drain() {
            while (avcodec_receive_packet(c, pkt) == 0) {
                out += ByteArray(pkt.size()).also { pkt.data().get(it) }
                av_packet_unref(pkt)
            }
        }
        repeat(frames) { i ->
            frame.pts(i.toLong())
            avcodec_send_frame(c, frame)
            drain()
        }
        avcodec_send_frame(c, null)
        drain()
        av_frame_free(frame)
        avcodec_free_context(c)
        return out
    }

    /** Raw AAC-LC frames (no ADTS), 44.1 kHz stereo: what AirPlay sends for ct=4. */
    private fun encodeAacLc(seconds: Int, hz: Double): List<ByteArray> {
        val codec = avcodec_find_encoder(AV_CODEC_ID_AAC)
        assumeNotNull(codec)
        val c: AVCodecContext = avcodec_alloc_context3(codec)
        c.sample_rate(44_100); c.sample_fmt(AV_SAMPLE_FMT_FLTP); c.bit_rate(128_000)
        av_channel_layout_default(c.ch_layout(), 2)
        c.time_base(AVRational().num(1).den(44_100))
        check(avcodec_open2(c, codec, null as AVDictionary?) >= 0)
        val n = c.frame_size()
        val frame: AVFrame = av_frame_alloc()
        frame.format(AV_SAMPLE_FMT_FLTP); frame.nb_samples(n); frame.sample_rate(44_100)
        av_channel_layout_default(frame.ch_layout(), 2)
        check(av_frame_get_buffer(frame, 0) >= 0)
        val pkt = av_packet_alloc()
        val out = mutableListOf<ByteArray>()
        fun drain() {
            while (avcodec_receive_packet(c, pkt) == 0) {
                out += ByteArray(pkt.size()).also { pkt.data().get(it) }
                av_packet_unref(pkt)
            }
        }
        var t = 0L
        repeat(seconds * 44_100 / n + 2) {
            val samples = FloatArray(n) { i -> (0.5 * sin(2 * PI * hz * (t + i) / 44_100)).toFloat() }
            for (ch in 0..1) FloatPointer(frame.data(ch)).put(samples, 0, n)
            frame.pts(t)
            t += n
            avcodec_send_frame(c, frame)
            drain()
        }
        avcodec_send_frame(c, null)
        drain()
        av_frame_free(frame)
        avcodec_free_context(c)
        return out
    }

    // endregion
}
