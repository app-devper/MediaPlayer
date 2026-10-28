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
internal fun jvmAudioOutput(pcmTap: ((ByteArray) -> Unit)? = null) =
    AudioThread(AudioPipeline(FfmpegAac::open, if (pcmTap != null) TapSink(pcmTap) else LineSink()))

/** AAC through FFmpeg, decoded as it's sent. */
private class FfmpegAac private constructor(private val ctx: AVCodecContext) : AacDecoder {
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private val decoded = ArrayDeque<ByteArray>()

    override fun send(frame: ByteArray, ptsUs: Long) {
        if (av_new_packet(packet, frame.size) < 0) return
        packet.data().put(frame, 0, frame.size)
        val sent = avcodec_send_packet(ctx, packet)
        av_packet_unref(packet)
        if (sent < 0) return
        while (avcodec_receive_frame(ctx, this.frame) == 0) decoded += toS16Interleaved(this.frame)
    }

    override fun drain(out: (ByteArray, Int, Int) -> Unit) {
        while (decoded.isNotEmpty()) out(decoded.removeFirst(), AudioPipeline.SAMPLE_RATE, 2)
    }

    override fun flush() = decoded.clear()

    override fun release() {
        avcodec_free_context(ctx)
        av_packet_free(packet)
        av_frame_free(frame)
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

    companion object {
        fun open(config: ByteArray): AacDecoder? {
            val decoder = avcodec_find_decoder(AV_CODEC_ID_AAC) ?: return null
            val c = avcodec_alloc_context3(decoder) ?: return null
            c.sample_rate(AudioPipeline.SAMPLE_RATE)
            av_channel_layout_default(c.ch_layout(), 2)
            val extra = BytePointer(av_mallocz((config.size + AV_INPUT_BUFFER_PADDING_SIZE).toLong()))
            extra.put(config, 0, config.size)
            c.extradata(extra)
            c.extradata_size(config.size)
            if (avcodec_open2(c, decoder, null as AVDictionary?) < 0) {
                avcodec_free_context(c)
                return null
            }
            return FfmpegAac(c)
        }
    }
}

/** The default sound output (44.1 kHz stereo, ~200 ms buffered). */
private class LineSink : PcmSink {
    @Volatile private var gain = 1f
    @Volatile private var line: SourceDataLine? = null

    override fun write(pcm: ByteArray, rate: Int, channels: Int) {
        val l = line ?: open() ?: return
        l.write(pcm, 0, pcm.size)
    }

    override fun flush() {
        line?.flush()
    }

    override fun setVolume(gain: Float) {
        this.gain = gain
        line?.let(::applyGain)
    }

    override fun release() {
        line?.close()
        line = null
    }

    private fun open(): SourceDataLine? = runCatching {
        val fmt = AudioFormat(AudioPipeline.SAMPLE_RATE.toFloat(), 16, 2, true, false)
        AudioSystem.getSourceDataLine(fmt).apply {
            open(fmt, AudioPipeline.SAMPLE_RATE / 5 * 4) // ~200 ms
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
}

/** Hands the PCM to [tap] instead of a sound card. */
private class TapSink(private val tap: (ByteArray) -> Unit) : PcmSink {
    override fun write(pcm: ByteArray, rate: Int, channels: Int) = tap(pcm)
    override fun flush() {}
    override fun setVolume(gain: Float) {}
    override fun release() {}
}
