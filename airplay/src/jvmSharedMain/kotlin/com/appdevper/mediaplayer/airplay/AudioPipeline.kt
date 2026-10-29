package com.appdevper.mediaplayer.airplay

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Decodes AirPlay's AAC (LC or ELD) to 16-bit PCM: MediaCodec on Android, FFmpeg on desktop. */
internal interface AacDecoder {
    fun send(frame: ByteArray, ptsUs: Long)

    /** Hands each decoded block to [out] (interleaved 16-bit PCM, its rate and channel count). */
    fun drain(out: (pcm: ByteArray, rate: Int, channels: Int) -> Unit)

    fun flush()
    fun release()
}

/** Where PCM is heard: an AudioTrack, a sound card's line. */
internal interface PcmSink {
    fun write(pcm: ByteArray, rate: Int, channels: Int)

    /** Drops what's buffered (the sender seeked or skipped). */
    fun flush()

    /** 0..1. */
    fun setVolume(gain: Float)
    fun release()
}

/**
 * AirPlay audio from frames to sound, on one thread: the format a sender announces picks the
 * decoder (AAC through [newAac], PCM straight through), frames never build up latency, a flush
 * empties decoder and output, and release frees both. The platforms give only [newAac] and [sink].
 */
internal class AudioPipeline(
    /** A decoder for the AudioSpecificConfig given, or null if the platform has none. */
    private val newAac: (config: ByteArray) -> AacDecoder?,
    private val sink: PcmSink,
) {
    private var format: AirPlayAudioCodec? = null
    private var decoder: AacDecoder? = null

    fun setFormat(codec: AirPlayAudioCodec?) {
        decoder?.release()
        decoder = null
        format = codec
        aacConfig(codec)?.let { decoder = newAac(it) }
    }

    fun frame(data: ByteArray, ptsUs: Long) {
        when (format) {
            AirPlayAudioCodec.PCM -> sink.write(data, SAMPLE_RATE, 2)
            AirPlayAudioCodec.AAC_LC, AirPlayAudioCodec.AAC_ELD -> decoder?.send(data, ptsUs)
            else -> Unit
        }
        drain()
    }

    /** Output a decoder finished since (MediaCodec's comes later than its input). */
    fun drain() {
        decoder?.drain(sink::write)
    }

    fun flush() {
        decoder?.flush()
        sink.flush()
    }

    fun setVolume(gain: Float) = sink.setVolume(gain)

    fun release() {
        decoder?.release()
        decoder = null
        sink.release()
    }

    companion object {
        const val SAMPLE_RATE = 44_100

        /** The AudioSpecificConfig AirPlay's AAC streams use (44.1 kHz stereo). */
        fun aacConfig(codec: AirPlayAudioCodec?): ByteArray? = when (codec) {
            AirPlayAudioCodec.AAC_LC -> byteArrayOf(0x12, 0x10)
            AirPlayAudioCodec.AAC_ELD -> byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
            else -> null
        }
    }
}

/** Runs an [AudioPipeline] on its own thread; calls return at once. */
internal class AudioThread(private val pipeline: AudioPipeline) {
    private sealed interface Msg {
        class Format(val codec: AirPlayAudioCodec?) : Msg
        class Frame(val data: ByteArray, val ptsUs: Long) : Msg
        data object Flush : Msg
        data object Quit : Msg
    }

    private val queue = LinkedBlockingQueue<Msg>()
    private val thread = Thread(::run, "airplay-audio").apply { isDaemon = true; start() }

    fun setFormat(codec: AirPlayAudioCodec?) = queue.offer(Msg.Format(codec))

    fun play(data: ByteArray, ptsNs: Long) {
        if (queue.size > MAX_QUEUED) queue.removeIf { it is Msg.Frame } // never build up latency
        queue.offer(Msg.Frame(data, ptsNs / 1000))
    }

    fun flush() = queue.offer(Msg.Flush)

    fun setVolume(gain: Float) = pipeline.setVolume(gain)

    fun release() {
        queue.offer(Msg.Quit)
        thread.join(1_000)
    }

    private fun run() {
        while (true) {
            val msg = queue.poll(20, TimeUnit.MILLISECONDS)
            if (msg == Msg.Quit) return pipeline.release()
            // A decoder or output that fails costs that message, not the thread (AirPlay audio until restart).
            runCatching {
                when (msg) {
                    is Msg.Format -> pipeline.setFormat(msg.codec)
                    is Msg.Frame -> pipeline.frame(msg.data, msg.ptsUs)
                    Msg.Flush -> pipeline.flush()
                    else -> pipeline.drain()
                }
            }
        }
    }

    private companion object {
        const val MAX_QUEUED = 50
    }
}
