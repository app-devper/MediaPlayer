package com.appdevper.mediaplayer.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_clone
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_seek_file
import org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_ALL
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_chlayout
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_sample_fmt
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.global.swscale.sws_alloc_context
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale_frame
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.roundToInt

/** One decoded picture, BGRA (Skia's BGRA_8888), rows packed. */
class BgraFrame(val width: Int, val height: Int, val bgra: ByteArray)

/** Where the player stands. [durationMs] is 0 for live / unknown. */
data class FfmpegPlayerStatus(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = true,
    val ended: Boolean = false,
    val error: String? = null,
)

/** Where decoded audio goes; [SoundCardOutput] by default, replaced in tests. */
interface PcmOutput {
    fun open(sampleRate: Int)
    /** Blocks while the device buffer is full (that paces playback). */
    fun write(pcm: ByteArray)
    /** Audio written but not yet heard. */
    fun bufferedMs(): Long
    fun pause()
    fun resume()
    fun flush()
    fun close()
}

/**
 * Desktop media player on FFmpeg: any URL FFmpeg opens (http/https, HLS from AirPlay video, local
 * DLNA servers), video as BGRA frames for Compose, audio to [PcmOutput]. Audio is the clock; video
 * frames wait for it (or for the wall clock when there is no audio).
 */
class FfmpegPlayer(
    private val url: String,
    autoPlay: Boolean,
    private val output: PcmOutput = SoundCardOutput(),
) {
    private val _status = MutableStateFlow(FfmpegPlayerStatus(playing = autoPlay))
    val status: StateFlow<FfmpegPlayerStatus> = _status.asStateFlow()

    private val _frames = MutableStateFlow<BgraFrame?>(null)
    val frames: StateFlow<BgraFrame?> = _frames.asStateFlow()

    @Volatile private var closing = false
    @Volatile private var paused = !autoPlay
    @Volatile private var gain = 1f
    @Volatile private var seekTarget = -1L
    // Bumped on every seek; queued packets/frames from before it are dropped.
    private val serial = AtomicInteger(0)

    private class Packet(val pkt: AVPacket?, val serial: Int, val flushTo: Long = -1L)

    private val videoQueue = LinkedBlockingQueue<Packet>(MAX_QUEUED)
    private val audioQueue = LinkedBlockingQueue<Packet>(MAX_QUEUED)

    @Volatile private var hasAudio = false
    @Volatile private var audioEnded = false
    @Volatile private var videoEnded = false
    // Audio clock: pts at the end of what was written to the line.
    @Volatile private var audioWrittenEndMs = -1L
    // Wall clock for streams without audio.
    @Volatile private var wallBaseMs = 0L
    @Volatile private var wallStartNs = System.nanoTime()

    private val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
        override fun call(opaque: Pointer?): Int = if (closing) 1 else 0
    }

    private val demuxThread = Thread(::demux, "ffmpeg-demux").apply { isDaemon = true }
    private val openedAtNs = System.nanoTime()

    private fun debug(message: String) {
        if (DEBUG) System.err.println("[player] $message (+${(System.nanoTime() - openedAtNs) / 1_000_000} ms)")
    }

    init {
        demuxThread.start()
    }

    fun play() {
        if (!paused) return
        if (_status.value.ended) seek(0)
        paused = false
        wallStartNs = System.nanoTime()
        output.resume()
        _status.update { it.copy(playing = true, ended = false) }
    }

    fun pause() {
        if (paused) return
        wallBaseMs = clockMs()
        paused = true
        output.pause()
        _status.update { it.copy(playing = false) }
    }

    fun seek(positionMs: Long) {
        // Until audio after the seek is heard, the wall clock runs from the target.
        wallBaseMs = positionMs.coerceAtLeast(0)
        wallStartNs = System.nanoTime()
        seekTarget = positionMs.coerceAtLeast(0)
        _status.update { it.copy(positionMs = positionMs.coerceAtLeast(0), ended = false, buffering = true) }
    }

    /** 0..1, applied to the samples. */
    fun setVolume(gain: Float) {
        this.gain = gain.coerceIn(0f, 1f)
    }

    /**
     * Silences at once and returns; the player's threads finish and free FFmpeg's resources on
     * their own (a network read is interrupted via [interrupt]). Never blocks the UI thread.
     */
    fun release() {
        closing = true
        output.close()
    }

    private fun clockMs(): Long = when {
        hasAudio && audioWrittenEndMs >= 0 -> (audioWrittenEndMs - output.bufferedMs()).coerceAtLeast(0)
        paused -> wallBaseMs
        else -> wallBaseMs + (System.nanoTime() - wallStartNs) / 1_000_000
    }

    // region demux

    private fun demux() {
        val fmt: AVFormatContext = avformat_alloc_context()
        fmt.interrupt_callback().callback(interrupt)
        val options = AVDictionary(null)
        // Only network protocols: a playlist from a sender must not reach local files.
        av_dict_set(options, "protocol_whitelist", "http,https,tcp,tls,crypto", 0)
        // YouTube's HLS segment URLs have no file extension; FFmpeg's HLS demuxer refuses those by
        // default. That check guards against playlists pointing at local files, which the
        // protocol whitelist above already rules out.
        av_dict_set(options, "extension_picky", "0", 0)
        av_dict_set(options, "rw_timeout", "15000000", 0)
        av_dict_set(options, "reconnect", "1", 0)
        // Probe little: HLS from YouTube lists many variants, and probing them all takes seconds.
        fmt.probesize(1_000_000)
        fmt.max_analyze_duration(1_000_000)
        debug("open $url")
        val opened = avformat_open_input(fmt, url, null, options)
        av_dict_free(options)
        if (opened < 0) {
            debug("open failed: $opened")
            fail("Can't open this media (error $opened)")
            return
        }
        var videoThread: Thread? = null
        var audioThread: Thread? = null
        try {
            if (avformat_find_stream_info(fmt, null as PointerPointer<*>?) < 0) {
                fail("Can't read this media")
                return
            }
            debug("stream info: ${fmt.nb_streams()} streams, duration ${fmt.duration()} us")
            val durationUs = fmt.duration()
            _status.update { it.copy(durationMs = if (durationUs == AV_NOPTS_VALUE || durationUs <= 0) 0 else durationUs / 1000) }

            val videoIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, null as PointerPointer<*>?, 0)
            val audioIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, videoIndex, null as PointerPointer<*>?, 0)
            hasAudio = audioIndex >= 0
            // Don't fetch what we won't play (HLS stops downloading other variants' segments).
            for (i in 0 until fmt.nb_streams()) {
                if (i != videoIndex && i != audioIndex) fmt.streams(i).discard(AVDISCARD_ALL)
            }
            debug("playing video stream $videoIndex, audio stream $audioIndex")
            if (videoIndex >= 0) videoThread = Thread({ videoLoop(fmt, videoIndex) }, "ffmpeg-video").apply { isDaemon = true; start() }
            else videoEnded = true
            if (audioIndex >= 0) audioThread = Thread({ audioLoop(fmt, audioIndex) }, "ffmpeg-audio").apply { isDaemon = true; start() }
            else audioEnded = true
            if (videoIndex < 0 && audioIndex < 0) {
                fail("No playable audio or video")
                return
            }

            val pkt = av_packet_alloc()
            var eof = false
            while (!closing) {
                val target = seekTarget
                if (target >= 0) {
                    seekTarget = -1
                    avformat_seek_file(fmt, -1, Long.MIN_VALUE, target * 1000, Long.MAX_VALUE, 0)
                    val s = serial.incrementAndGet()
                    listOf(videoQueue, audioQueue).forEach { q -> q.forEach { it.pkt?.let(::freePacket) }; q.clear() }
                    if (videoIndex >= 0) put(videoQueue, Packet(null, s, flushTo = target))
                    if (audioIndex >= 0) put(audioQueue, Packet(null, s, flushTo = target))
                    audioEnded = audioIndex < 0
                    videoEnded = videoIndex < 0
                    eof = false
                }
                if (eof) {
                    if (audioEnded && videoEnded && (!hasAudio || output.bufferedMs() <= 0)) {
                        _status.update { it.copy(ended = true, playing = false, buffering = false) }
                        paused = true
                    }
                    Thread.sleep(20)
                    continue
                }
                val read = av_read_frame(fmt, pkt)
                if (read < 0) {
                    eof = true
                    if (videoIndex >= 0) put(videoQueue, Packet(null, serial.get()))
                    if (audioIndex >= 0) put(audioQueue, Packet(null, serial.get()))
                    continue
                }
                when (pkt.stream_index()) {
                    videoIndex -> put(videoQueue, Packet(av_packet_clone(pkt), serial.get()))
                    audioIndex -> put(audioQueue, Packet(av_packet_clone(pkt), serial.get()))
                }
                av_packet_unref(pkt)
            }
            av_packet_free(pkt)
        } finally {
            closing = true
            videoThread?.join(1_000)
            audioThread?.join(1_000)
            listOf(videoQueue, audioQueue).forEach { q -> q.forEach { it.pkt?.let(::freePacket) }; q.clear() }
            avformat_close_input(fmt)
        }
    }

    /** Blocks while the queue is full (backpressure), but never past a seek or close. */
    private fun put(queue: LinkedBlockingQueue<Packet>, p: Packet) {
        while (!closing && seekTarget < 0) {
            if (queue.offer(p, 50, TimeUnit.MILLISECONDS)) return
        }
        p.pkt?.let(::freePacket)
    }

    private fun freePacket(pkt: AVPacket) = av_packet_free(pkt)

    private fun fail(message: String) {
        debug("error: $message")
        _status.update { it.copy(error = message, playing = false, buffering = false) }
    }

    // endregion

    // region decoding

    private fun openDecoder(fmt: AVFormatContext, index: Int): AVCodecContext? {
        val par = fmt.streams(index).codecpar()
        val codec = avcodec_find_decoder(par.codec_id()) ?: return null
        val ctx = avcodec_alloc_context3(codec) ?: return null
        if (avcodec_parameters_to_context(ctx, par) < 0 || avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            return null
        }
        return ctx
    }

    private fun ptsMs(fmt: AVFormatContext, index: Int, pts: Long): Long {
        if (pts == AV_NOPTS_VALUE) return -1
        val tb = fmt.streams(index).time_base()
        val startUs = fmt.start_time().takeIf { it != AV_NOPTS_VALUE } ?: 0
        return pts * 1000 * tb.num() / tb.den() - startUs / 1000
    }

    private fun videoLoop(fmt: AVFormatContext, index: Int) {
        val ctx = openDecoder(fmt, index) ?: run { videoEnded = true; return }
        val frame = av_frame_alloc()
        val bgra = av_frame_alloc()
        var sws: SwsContext? = null
        var skipBefore = -1L
        try {
            while (!closing) {
                val p = videoQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (p.serial != serial.get()) { p.pkt?.let(::freePacket); continue }
                if (p.flushTo >= 0) {
                    avcodec_flush_buffers(ctx)
                    skipBefore = p.flushTo
                    continue
                }
                val sent = avcodec_send_packet(ctx, p.pkt) // null pkt = drain at end of stream
                p.pkt?.let(::freePacket)
                if (sent < 0 && p.pkt != null) continue
                while (!closing && avcodec_receive_frame(ctx, frame) == 0) {
                    val pts = ptsMs(fmt, index, frame.best_effort_timestamp())
                    if (skipBefore >= 0 && pts in 0 until skipBefore) { av_frame_unref(frame); continue }
                    skipBefore = -1
                    if (!waitUntil(pts, p.serial)) { av_frame_unref(frame); continue }
                    val w = frame.width()
                    val h = frame.height()
                    if (bgra.width() != w || bgra.height() != h) {
                        av_frame_unref(bgra)
                        bgra.format(AV_PIX_FMT_BGRA); bgra.width(w); bgra.height(h)
                        av_frame_get_buffer(bgra, 0)
                    }
                    val s = sws ?: sws_alloc_context()?.also { sws = it }
                    if (s != null && sws_scale_frame(s, bgra, frame) >= 0) {
                        if (_frames.value == null) debug("first picture ${w}x$h at $pts ms")
                        _frames.value = BgraFrame(w, h, packedRows(bgra, w, h))
                    }
                    if (!hasAudio) _status.update { it.copy(positionMs = maxOf(pts, 0), buffering = false) }
                    av_frame_unref(frame)
                }
                if (p.pkt == null) videoEnded = true
            }
        } finally {
            av_frame_free(frame)
            av_frame_free(bgra)
            sws?.let { sws_freeContext(it) }
            avcodec_free_context(ctx)
        }
    }

    /**
     * Holds a frame until the clock reaches it. False = drop it (far behind, or a seek happened).
     */
    private fun waitUntil(ptsMs: Long, frameSerial: Int): Boolean {
        if (ptsMs < 0) return true
        while (!closing && frameSerial == serial.get() && seekTarget < 0) {
            if (paused && _frames.value != null) {
                Thread.sleep(10)
                continue
            }
            val ahead = ptsMs - clockMs()
            if (ahead <= 5) return ahead > -LATE_DROP_MS || _frames.value == null
            Thread.sleep(minOf(ahead, 10))
        }
        return false
    }

    private fun packedRows(f: AVFrame, w: Int, h: Int): ByteArray {
        val row = w * 4
        val stride = f.linesize(0)
        val src = f.data(0)
        val out = ByteArray(row * h)
        if (stride == row) {
            src.get(out, 0, out.size)
        } else {
            for (y in 0 until h) src.position(y.toLong() * stride).get(out, y * row, row)
            src.position(0)
        }
        return out
    }

    private fun audioLoop(fmt: AVFormatContext, index: Int) {
        val ctx = openDecoder(fmt, index) ?: run { audioEnded = true; hasAudio = false; return }
        val rate = ctx.sample_rate().takeIf { it > 0 } ?: 48_000
        val swr: SwrContext = swr_alloc()
        av_opt_set_chlayout(swr, "in_chlayout", ctx.ch_layout(), 0)
        av_opt_set_int(swr, "in_sample_rate", rate.toLong(), 0)
        av_opt_set_sample_fmt(swr, "in_sample_fmt", ctx.sample_fmt(), 0)
        val stereo = org.bytedeco.ffmpeg.avutil.AVChannelLayout().also { av_channel_layout_default(it, 2) }
        av_opt_set_chlayout(swr, "out_chlayout", stereo, 0)
        av_opt_set_int(swr, "out_sample_rate", rate.toLong(), 0)
        av_opt_set_sample_fmt(swr, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0)
        swr_init(swr)
        output.open(rate)
        if (paused) output.pause()
        val frame = av_frame_alloc()
        var outBuf = BytePointer(0L)
        var skipBefore = -1L
        try {
            while (!closing) {
                if (paused) { Thread.sleep(10); continue }
                val p = audioQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (p.serial != serial.get()) { p.pkt?.let(::freePacket); continue }
                if (p.flushTo >= 0) {
                    avcodec_flush_buffers(ctx)
                    output.flush()
                    audioWrittenEndMs = -1
                    skipBefore = p.flushTo
                    continue
                }
                val sent = avcodec_send_packet(ctx, p.pkt)
                p.pkt?.let(::freePacket)
                if (sent < 0 && p.pkt != null) continue
                while (!closing && p.serial == serial.get() && avcodec_receive_frame(ctx, frame) == 0) {
                    val pts = ptsMs(fmt, index, frame.best_effort_timestamp())
                    val samples = frame.nb_samples()
                    val frameMs = samples * 1000L / rate
                    if (skipBefore >= 0 && pts >= 0 && pts + frameMs < skipBefore) { av_frame_unref(frame); continue }
                    skipBefore = -1
                    val bytes = samples * 4
                    if (outBuf.capacity() < bytes) {
                        outBuf.close()
                        outBuf = BytePointer(bytes.toLong() * 2)
                    }
                    // Not PointerPointer(outBuf): that's JavaCPP's cast constructor (outBuf's bytes read as pointers).
                    val outPlanes = PointerPointer<BytePointer>(1L).put(0, outBuf)
                    val converted = swr_convert(swr, outPlanes, samples, frame.extended_data(), samples)
                    outPlanes.close()
                    av_frame_unref(frame)
                    if (converted <= 0) continue
                    val pcm = ByteArray(converted * 4).also { outBuf.position(0).get(it) }
                    applyGain(pcm)
                    output.write(pcm)
                    if (p.serial != serial.get()) break
                    audioWrittenEndMs = (if (pts >= 0) pts else maxOf(audioWrittenEndMs, 0)) + converted * 1000L / rate
                    _status.update { it.copy(positionMs = clockMs(), buffering = false) }
                }
                if (p.pkt == null) audioEnded = true
            }
        } finally {
            av_frame_free(frame)
            outBuf.close()
            swr_free(swr)
            avcodec_free_context(ctx)
        }
    }

    private fun applyGain(pcm: ByteArray) {
        val g = gain
        if (g >= 0.999f) return
        for (i in pcm.indices step 2) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort()
            val v = (s * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = v.toByte()
            pcm[i + 1] = (v shr 8).toByte()
        }
    }

    // endregion

    private companion object {
        const val MAX_QUEUED = 256
        const val LATE_DROP_MS = 150

        /** AIRPLAY_LOG=info|debug also logs the player (and FFmpeg's own warnings). */
        val DEBUG = System.getenv("AIRPLAY_LOG") != null

        init {
            if (DEBUG) av_log_set_level(AV_LOG_WARNING)
        }
    }
}

/** The default sound device through javax.sound (16-bit stereo). */
class SoundCardOutput : PcmOutput {
    private var line: SourceDataLine? = null
    private var bytesPerMs = 0.0

    override fun open(sampleRate: Int) {
        val fmt = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
        line = runCatching {
            AudioSystem.getSourceDataLine(fmt).apply {
                open(fmt, sampleRate / 4 * 4) // ~250 ms
                start()
            }
        }.getOrNull()
        bytesPerMs = sampleRate * 4 / 1000.0
    }

    override fun write(pcm: ByteArray) {
        val l = line ?: run { Thread.sleep((pcm.size / maxOf(bytesPerMs, 1.0)).toLong()); return } // no device: keep time
        if (l.isOpen) runCatching { l.write(pcm, 0, pcm.size) } // closed by release() meanwhile: drop
    }

    override fun bufferedMs(): Long {
        val l = line ?: return 0
        return (abs(l.bufferSize - l.available()) / maxOf(bytesPerMs, 1.0)).toLong()
    }

    override fun pause() { line?.stop() }
    override fun resume() { line?.start() }
    override fun flush() { line?.flush() }
    override fun close() {
        line?.let { it.stop(); it.flush(); it.close() }
    }
}
