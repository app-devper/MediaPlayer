package com.appdevper.mediaplayer.airplay

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.AV_INPUT_BUFFER_PADDING_SIZE
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_mallocz
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.ShortPointer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AirPlay audio → the default output: AAC-LC/AAC-ELD through FFmpeg, PCM straight through.
 * [pcmTap] replaces the sound card (tests).
 */
internal class JvmAudioOutput(private val pcmTap: ((ByteArray) -> Unit)? = null) {
    private sealed interface Msg {
        class Format(val codec: AirPlayAudioCodec?) : Msg
        class Frame(val data: ByteArray) : Msg
        data object Flush : Msg
        data object Quit : Msg
    }

    private val queue = LinkedBlockingQueue<Msg>()
    private val thread = Thread(::run, "airplay-audio").apply { isDaemon = true; start() }

    @Volatile private var gain = 1f
    private var format: AirPlayAudioCodec? = null
    private var ctx: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var line: SourceDataLine? = null

    fun setFormat(codec: AirPlayAudioCodec?) = queue.offer(Msg.Format(codec))

    fun play(data: ByteArray) {
        if (queue.size > MAX_QUEUED) queue.removeIf { it is Msg.Frame }
        queue.offer(Msg.Frame(data))
    }

    fun flush() = queue.offer(Msg.Flush)

    fun setVolume(gain: Float) {
        this.gain = gain
        line?.let(::applyGain)
    }

    fun release() {
        queue.offer(Msg.Quit)
        thread.join(1_000)
    }

    private fun run() {
        while (true) {
            when (val msg = queue.poll(50, TimeUnit.MILLISECONDS)) {
                is Msg.Format -> configure(msg.codec)
                is Msg.Frame -> runCatching { onFrame(msg.data) }
                Msg.Flush -> line?.flush()
                Msg.Quit -> {
                    closeDecoder()
                    line?.close()
                    return
                }
                null -> Unit
            }
        }
    }

    private fun configure(codec: AirPlayAudioCodec?) {
        closeDecoder()
        format = codec
        val config = when (codec) {
            AirPlayAudioCodec.AAC_LC -> byteArrayOf(0x12, 0x10)
            AirPlayAudioCodec.AAC_ELD -> byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
            else -> return
        }
        val decoder = avcodec_find_decoder(AV_CODEC_ID_AAC) ?: return
        val c = avcodec_alloc_context3(decoder) ?: return
        c.sample_rate(SAMPLE_RATE)
        av_channel_layout_default(c.ch_layout(), 2)
        val extra = BytePointer(av_mallocz((config.size + AV_INPUT_BUFFER_PADDING_SIZE).toLong()))
        extra.put(config, 0, config.size)
        c.extradata(extra)
        c.extradata_size(config.size)
        if (avcodec_open2(c, decoder, null as AVDictionary?) < 0) {
            avcodec_free_context(c)
            return
        }
        ctx = c
        packet = av_packet_alloc()
        frame = av_frame_alloc()
    }

    private fun onFrame(data: ByteArray) {
        when (format) {
            AirPlayAudioCodec.PCM -> write(data)
            AirPlayAudioCodec.AAC_LC, AirPlayAudioCodec.AAC_ELD -> decode(data)
            else -> Unit
        }
    }

    private fun decode(data: ByteArray) {
        val c = ctx ?: return
        val p = packet ?: return
        if (av_new_packet(p, data.size) < 0) return
        p.data().put(data, 0, data.size)
        val sent = avcodec_send_packet(c, p)
        av_packet_unref(p)
        if (sent < 0) return
        val f = frame ?: return
        while (avcodec_receive_frame(c, f) == 0) write(toS16Interleaved(f))
    }

    /** FFmpeg's AAC decoder outputs planar float; the line wants interleaved 16-bit. */
    private fun toS16Interleaved(f: AVFrame): ByteArray {
        val samples = f.nb_samples()
        val channels = f.ch_layout().nb_channels()
        val out = ByteArray(samples * 2 * 2)
        when (f.format()) {
            AV_SAMPLE_FMT_FLTP -> {
                val planes = (0 until minOf(channels, 2)).map { ch -> FloatPointer(f.data(ch)).also { it.capacity(samples.toLong()) } }
                for (i in 0 until samples) for (ch in 0 until 2) {
                    val v = planes[minOf(ch, planes.lastIndex)].get(i.toLong()).coerceIn(-1f, 1f)
                    val s = (v * Short.MAX_VALUE).roundToInt()
                    val o = (i * 2 + ch) * 2
                    out[o] = s.toByte()
                    out[o + 1] = (s shr 8).toByte()
                }
            }
            AV_SAMPLE_FMT_S16 -> {
                val src = ShortPointer(f.data(0)).also { it.capacity(samples.toLong() * channels) }
                for (i in 0 until samples) for (ch in 0 until 2) {
                    val s = src.get((i * channels + minOf(ch, channels - 1)).toLong()).toInt()
                    val o = (i * 2 + ch) * 2
                    out[o] = s.toByte()
                    out[o + 1] = (s shr 8).toByte()
                }
            }
        }
        return out
    }

    private fun write(pcm: ByteArray) {
        pcmTap?.let { return it(pcm) }
        val l = line ?: openLine() ?: return
        l.write(pcm, 0, pcm.size)
    }

    private fun openLine(): SourceDataLine? = runCatching {
        val fmt = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
        AudioSystem.getSourceDataLine(fmt).apply {
            open(fmt, SAMPLE_RATE / 5 * 4) // ~200 ms
            applyGain(this)
            start()
        }
    }.getOrNull().also { line = it }

    private fun applyGain(l: SourceDataLine) {
        if (!l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val control = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val db = if (gain <= 0f) control.minimum else 20f * log10(gain)
        control.value = max(control.minimum, db.coerceAtMost(control.maximum))
    }

    private fun closeDecoder() {
        ctx?.let { avcodec_free_context(it) }
        packet?.let { av_packet_free(it) }
        frame?.let { av_frame_free(it) }
        ctx = null
        packet = null
        frame = null
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val MAX_QUEUED = 50
    }
}
