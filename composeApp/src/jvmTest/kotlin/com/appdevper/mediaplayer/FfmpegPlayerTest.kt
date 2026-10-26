package com.appdevper.mediaplayer

import com.appdevper.mediaplayer.platform.FfmpegPlayer
import com.appdevper.mediaplayer.platform.PcmOutput
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.javacpp.FloatPointer
import org.junit.Assume.assumeNotNull
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The desktop player on a real HLS stream (H.264 + AAC), like AirPlay video from YouTube. */
class FfmpegPlayerTest {
    private val dir: File = Files.createTempDirectory("hls").toFile()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val f = File(dir, ex.requestURI.path.removePrefix("/"))
            if (!f.isFile) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(200, f.length())
            ex.responseBody.use { it.write(f.readBytes()) }
        }
        start()
    }
    private val url get() = "http://127.0.0.1:${server.address.port}/index.m3u8"
    private var player: FfmpegPlayer? = null

    @AfterTest
    fun tearDown() {
        player?.release()
        server.stop(0)
        dir.deleteRecursively()
    }

    /** Like a sound card: takes audio in real time. */
    private class RealTimeOutput : PcmOutput {
        @Volatile var paused = false
        @Volatile var bytes = 0L
        private var bytesPerMs = 176.4
        override fun open(sampleRate: Int) { bytesPerMs = sampleRate * 4 / 1000.0 }
        override fun write(pcm: ByteArray) {
            while (paused) Thread.sleep(5)
            bytes += pcm.size
            Thread.sleep((pcm.size / bytesPerMs).toLong())
        }
        override fun bufferedMs() = 0L
        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun flush() {}
        override fun close() {}
    }

    @Test
    fun playsSeeksPausesAndEndsAnHlsStream() = runBlocking<Unit> {
        writeHls(seconds = 4)
        val audio = RealTimeOutput()
        val p = FfmpegPlayer(url, autoPlay = true, output = audio).also { player = it }

        val frame = withTimeout(10_000) { p.frames.first { it != null } }!!
        assertTrue(frame.width == 320 && frame.height == 240, "${frame.width}x${frame.height}")
        val center = (120 * 320 + 160) * 4
        val (b, g, r) = Triple(frame.bgra[center].toInt() and 0xff, frame.bgra[center + 1].toInt() and 0xff, frame.bgra[center + 2].toInt() and 0xff)
        assertTrue(r > 200 && g < 60 && b < 60, "expected red, got r=$r g=$g b=$b")

        val playing = withTimeout(10_000) { p.status.first { it.positionMs > 500 } }
        assertTrue(playing.durationMs in 3_800..4_300, "duration ${playing.durationMs}")
        assertTrue(audio.bytes > 0, "audio reached the output")

        p.pause()
        Thread.sleep(300)
        val heldAt = p.status.value.positionMs
        Thread.sleep(500)
        assertTrue(p.status.value.positionMs - heldAt < 100, "position moved while paused: $heldAt → ${p.status.value.positionMs}")

        p.seek(2_500)
        p.play()
        val afterSeek = withTimeout(10_000) { p.status.first { it.positionMs >= 2_500 && !it.buffering } }
        assertTrue(afterSeek.positionMs < 3_500, "seeked to ${afterSeek.positionMs}")

        val ended = withTimeout(15_000) { p.status.first { it.ended } }
        assertTrue(!ended.playing)
    }

    @Test
    fun localFilesAreRefused() = runBlocking<Unit> {
        val secret = File(dir, "secret.m3u8").apply { writeText("#EXTM3U\n") }
        val p = FfmpegPlayer("file://${secret.absolutePath}", autoPlay = true, output = RealTimeOutput()).also { player = it }
        val failed = withTimeout(10_000) { p.status.first { it.error != null } }
        assertTrue(failed.error!!.isNotEmpty())
    }

    /** A VOD HLS stream (1 s segments) of red 320x240 video and a 440 Hz tone. */
    private fun writeHls(seconds: Int) {
        val h264 = avcodec_find_encoder(AV_CODEC_ID_H264)
        val aac = avcodec_find_encoder(AV_CODEC_ID_AAC)
        assumeNotNull(h264, aac)
        val out = AVFormatContext(null)
        check(avformat_alloc_output_context2(out, null, "hls", File(dir, "index.m3u8").absolutePath) >= 0)

        val v: AVCodecContext = avcodec_alloc_context3(h264)
        v.width(320); v.height(240); v.pix_fmt(AV_PIX_FMT_YUV420P)
        v.time_base(AVRational().num(1).den(25)); v.framerate(AVRational().num(25).den(1))
        v.gop_size(25); v.bit_rate(400_000)
        check(avcodec_open2(v, h264, null as AVDictionary?) >= 0)
        val vs = avformat_new_stream(out, null)
        avcodec_parameters_from_context(vs.codecpar(), v)
        vs.time_base(v.time_base())

        val a: AVCodecContext = avcodec_alloc_context3(aac)
        a.sample_rate(44_100); a.sample_fmt(AV_SAMPLE_FMT_FLTP); a.bit_rate(128_000)
        av_channel_layout_default(a.ch_layout(), 2)
        a.time_base(AVRational().num(1).den(44_100))
        check(avcodec_open2(a, aac, null as AVDictionary?) >= 0)
        val aStream = avformat_new_stream(out, null)
        avcodec_parameters_from_context(aStream.codecpar(), a)
        aStream.time_base(a.time_base())

        val opts = AVDictionary(null)
        av_dict_set(opts, "hls_time", "1", 0)
        av_dict_set(opts, "hls_list_size", "0", 0)
        av_dict_set(opts, "hls_playlist_type", "vod", 0)
        check(avformat_write_header(out, opts) >= 0)

        val pkt = av_packet_alloc()
        fun drain(ctx: AVCodecContext, streamIndex: Int, tb: AVRational) {
            while (avcodec_receive_packet(ctx, pkt) == 0) {
                av_packet_rescale_ts(pkt, ctx.time_base(), tb)
                pkt.stream_index(streamIndex)
                av_interleaved_write_frame(out, pkt)
                av_packet_unref(pkt)
            }
        }

        val vf: AVFrame = av_frame_alloc().apply { format(AV_PIX_FMT_YUV420P); width(320); height(240) }
        check(av_frame_get_buffer(vf, 0) >= 0)
        for (plane in 0..2) {
            val h = if (plane == 0) 240 else 120
            val bytes = ByteArray(vf.linesize(plane) * h) { byteArrayOf(81, 90, 240.toByte())[plane] }
            vf.data(plane).put(bytes, 0, bytes.size)
        }
        val n = a.frame_size()
        val af: AVFrame = av_frame_alloc().apply { format(AV_SAMPLE_FMT_FLTP); nb_samples(n); sample_rate(44_100) }
        av_channel_layout_default(af.ch_layout(), 2)
        check(av_frame_get_buffer(af, 0) >= 0)

        var videoPts = 0L
        var audioPts = 0L
        while (videoPts < seconds * 25L || audioPts < seconds * 44_100L) {
            // Interleave by time so the muxer gets both streams evenly.
            if (videoPts * 44_100 / 25 <= audioPts && videoPts < seconds * 25L) {
                vf.pts(videoPts++)
                avcodec_send_frame(v, vf)
                drain(v, 0, vs.time_base())
            } else {
                val samples = FloatArray(n) { i -> (0.4 * sin(2 * PI * 440 * (audioPts + i) / 44_100)).toFloat() }
                for (ch in 0..1) FloatPointer(af.data(ch)).put(samples, 0, n)
                af.pts(audioPts)
                audioPts += n
                avcodec_send_frame(a, af)
                drain(a, 1, aStream.time_base())
            }
        }
        avcodec_send_frame(v, null); drain(v, 0, vs.time_base())
        avcodec_send_frame(a, null); drain(a, 1, aStream.time_base())
        av_write_trailer(out)
        avformat_free_context(out)
        check(File(dir, "index.m3u8").readText().contains("#EXT-X-ENDLIST"))
    }
}
