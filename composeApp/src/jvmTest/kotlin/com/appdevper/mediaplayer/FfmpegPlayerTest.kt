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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop player on a real HLS stream (H.264 + AAC), like AirPlay video from YouTube. */
class FfmpegPlayerTest {
    private val dir: File = Files.createTempDirectory("hls").toFile()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            if (ex.requestURI.path.endsWith(".ts")) Thread.sleep(segmentDelayMs)
            val f = File(dir, ex.requestURI.path.removePrefix("/"))
            if (!f.isFile) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            ex.sendResponseHeaders(200, f.length())
            ex.responseBody.use { it.write(f.readBytes()) }
        }
        start()
    }
    private val url get() = "http://127.0.0.1:${server.address.port}/index.m3u8"
    // A slow network (YouTube over the internet): the demuxer's seek then takes a while.
    @Volatile private var segmentDelayMs = 0L
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
        @Volatile var flushes = 0
        override fun flush() { flushes++ }
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

    @Test
    fun aVideoWithoutAudioPlaysOnAfterASeek() = runBlocking<Unit> {
        // No audio: the video's own clock times the frames. Reporting the seek target must not stop it.
        writeHls(seconds = 6, audioSeconds = 0)
        val p = FfmpegPlayer(url, autoPlay = true, output = RealTimeOutput()).also { player = it }
        withTimeout(10_000) { p.status.first { it.positionMs > 500 } }
        p.seek(2_510) // between two frames (25 fps)
        val moving = withTimeout(10_000) { p.status.first { it.positionMs > 3_200 } }
        assertTrue(moving.positionMs < 5_000, "played on from the seek: ${moving.positionMs}")
    }

    @Test
    fun theLastOfTwoQuickSeeksWins() = runBlocking<Unit> {
        // The demuxer used to read the target, then clear it: a seek landing in between was lost.
        writeHls(seconds = 6)
        val p = FfmpegPlayer(url, autoPlay = true, output = RealTimeOutput()).also { player = it }
        withTimeout(10_000) { p.status.first { it.positionMs > 500 } }
        repeat(20) { i ->
            p.seek(1_000)
            if (i % 2 == 0) Thread.sleep(1)
            p.seek(4_500)
            val at = withTimeout(10_000) { p.status.first { !it.buffering && it.positionMs >= 4_500 } }
            assertTrue(at.positionMs < 5_600, "round $i: played from ${at.positionMs}, not 4.5 s")
        }
    }

    @Test
    fun aSeekWhilePausedDropsTheOldAudio() = runBlocking<Unit> {
        writeHls(seconds = 6)
        val out = RealTimeOutput()
        val p = FfmpegPlayer(url, autoPlay = true, output = out).also { player = it }
        withTimeout(10_000) { p.status.first { it.positionMs > 500 } }
        p.pause()
        val before = out.flushes
        p.seek(4_000)
        assertTrue(out.flushes > before, "buffered audio from before the seek was kept")
    }

    @Test
    fun anAudioTrackShorterThanTheVideoDoesntFreezeThePosition() = runBlocking<Unit> {
        // Seek past the audio's end: video is the clock there, the position must move on.
        writeHls(seconds = 6, audioSeconds = 2)
        val p = FfmpegPlayer(url, autoPlay = true, output = RealTimeOutput()).also { player = it }
        withTimeout(10_000) { p.status.first { it.positionMs > 300 } }
        p.seek(3_500)
        val moving = withTimeout(10_000) { p.status.first { it.positionMs > 4_200 } }
        assertTrue(moving.positionMs < 6_200)
    }

    @Test
    fun aSeekRightBeforeAPauseKeepsItsPosition() = runBlocking<Unit> {
        // YouTube's drag: /scrub then /rate 0 at once. The old audio finishing must not put the position
        // back, and while paused (decoders idle) the target is what's reported.
        writeHls(seconds = 6)
        val p = FfmpegPlayer(url, autoPlay = true, output = RealTimeOutput()).also { player = it }
        withTimeout(10_000) { p.status.first { it.positionMs > 1_500 } }
        segmentDelayMs = 600
        p.seek(4_200)
        p.pause()
        repeat(10) {
            Thread.sleep(100)
            assertEquals(4_200, p.status.value.positionMs, "position while paused after the seek")
        }
        segmentDelayMs = 0
        p.play()
        val after = withTimeout(10_000) { p.status.first { it.positionMs > 4_300 && !it.buffering } }
        assertTrue(after.positionMs < 5_500, "played on from the seek: ${after.positionMs}")
    }

    @Test
    fun survivesManyQuickSeeksAndReleases() = runBlocking<Unit> {
        // Seeking cleared the packet queues while the decoders took from them: a packet could be freed
        // twice (JVM crash in av_buffer_unref). Many seeks while both decoders are busy, then release.
        writeHls(seconds = 6)
        repeat(3) { round ->
            val p = FfmpegPlayer(url, autoPlay = true, output = RealTimeOutput()).also { player = it }
            withTimeout(10_000) { p.status.first { it.positionMs > 200 } }
            val random = java.util.Random(round.toLong())
            repeat(150) {
                p.seek(random.nextInt(5_500).toLong())
                Thread.sleep(random.nextInt(4).toLong())
            }
            // Then a last seek, which must really land (not a stale frame from before it).
            p.seek(3_000)
            val settled = withTimeout(10_000) { p.status.first { !it.buffering && it.positionMs in 3_000..4_500 } }
            assertTrue(settled.error == null, "error ${settled.error}")
            p.release()
            assertTrue(p.awaitStopped(5_000), "player threads still running after release")
            player = null
        }
    }

    /** A VOD HLS stream (1 s segments) of red 320x240 video and a 440 Hz tone. */
    @Test
    fun seeksInAMultiVariantStream() = runBlocking<Unit> {
        // Like YouTube: a master playlist listing several variants; only one is played.
        writeHls(seconds = 6, sub = "lo")
        writeHls(seconds = 6, sub = "hi")
        File(dir, "master.m3u8").writeText(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=320x240\nlo/index.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=320x240\nhi/index.m3u8\n",
        )
        val p = FfmpegPlayer("http://127.0.0.1:${server.address.port}/master.m3u8", autoPlay = true, output = RealTimeOutput()).also { player = it }
        withTimeout(10_000) { p.status.first { it.positionMs > 300 } }
        p.seek(4_000)
        val afterSeek = withTimeout(10_000) { p.status.first { it.positionMs >= 4_000 && !it.buffering } }
        assertTrue(afterSeek.positionMs < 5_500, "seeked to ${afterSeek.positionMs}")
        assertTrue(afterSeek.error == null, "error ${afterSeek.error}")
    }

    /** [audioSeconds]: how much of it has audio (0 = none, less than [seconds] = audio ends early). */
    private fun writeHls(seconds: Int, sub: String = "", audioSeconds: Int = seconds) {
        val withAudio = audioSeconds > 0
        val target = File(dir, sub).apply { mkdirs() }
        val h264 = avcodec_find_encoder(AV_CODEC_ID_H264)
        val aac = avcodec_find_encoder(AV_CODEC_ID_AAC)
        assumeNotNull(h264, aac)
        val out = AVFormatContext(null)
        check(avformat_alloc_output_context2(out, null, "hls", File(target, "index.m3u8").absolutePath) >= 0)

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
        val aStream = if (!withAudio) null else avformat_new_stream(out, null).also {
            avcodec_parameters_from_context(it.codecpar(), a)
            it.time_base(a.time_base())
        }

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
        while (videoPts < seconds * 25L || audioPts < audioSeconds * 44_100L) {
            // Interleave by time so the muxer gets both streams evenly.
            val audioDone = audioPts >= audioSeconds * 44_100L
            if (videoPts < seconds * 25L && (audioDone || videoPts * 44_100 / 25 <= audioPts)) {
                vf.pts(videoPts++)
                avcodec_send_frame(v, vf)
                drain(v, 0, vs.time_base())
            } else {
                val samples = FloatArray(n) { i -> (0.4 * sin(2 * PI * 440 * (audioPts + i) / 44_100)).toFloat() }
                for (ch in 0..1) FloatPointer(af.data(ch)).put(samples, 0, n)
                af.pts(audioPts)
                audioPts += n
                avcodec_send_frame(a, af)
                drain(a, 1, aStream!!.time_base())
            }
        }
        avcodec_send_frame(v, null); drain(v, 0, vs.time_base())
        if (aStream != null) { avcodec_send_frame(a, null); drain(a, 1, aStream.time_base()) }
        av_write_trailer(out)
        avformat_free_context(out)
        check(File(target, "index.m3u8").readText().contains("#EXT-X-ENDLIST"))
    }
}
