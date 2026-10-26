package com.appdevper.mediaplayer.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** AirPlay audio → AudioTrack: AAC-LC/AAC-ELD through MediaCodec, PCM straight through. */
internal class AudioOutput {
    private sealed interface Msg {
        class Format(val codec: AirPlayAudioCodec?) : Msg
        class Frame(val data: ByteArray, val ptsUs: Long) : Msg
        data object Flush : Msg
        data object Quit : Msg
    }

    private val queue = LinkedBlockingQueue<Msg>()
    private val thread = Thread(::run, "airplay-audio").apply { isDaemon = true; start() }

    @Volatile private var gain = 1f
    private var format: AirPlayAudioCodec? = null
    private var decoder: MediaCodec? = null
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0
    private val info = MediaCodec.BufferInfo()

    fun setFormat(codec: AirPlayAudioCodec?) = queue.offer(Msg.Format(codec))

    fun play(data: ByteArray, ptsNs: Long) {
        if (queue.size > MAX_QUEUED) queue.removeIf { it is Msg.Frame } // never build up latency
        queue.offer(Msg.Frame(data, ptsNs / 1000))
    }

    fun flush() = queue.offer(Msg.Flush)

    fun setVolume(gain: Float) {
        this.gain = gain
        track?.setVolume(gain)
    }

    fun release() {
        queue.offer(Msg.Quit)
        thread.join(1_000)
    }

    private fun run() {
        while (true) {
            when (val msg = queue.poll(20, TimeUnit.MILLISECONDS)) {
                is Msg.Format -> configure(msg.codec)
                is Msg.Frame -> onFrame(msg)
                Msg.Flush -> {
                    runCatching { decoder?.flush() }
                    track?.let { it.pause(); it.flush(); it.play() }
                }
                Msg.Quit -> {
                    releaseAll()
                    return
                }
                null -> Unit
            }
            drain()
        }
    }

    private fun configure(codec: AirPlayAudioCodec?) {
        releaseAll()
        format = codec
        val config = when (codec) {
            AirPlayAudioCodec.AAC_LC -> byteArrayOf(0x12, 0x10)
            AirPlayAudioCodec.AAC_ELD -> byteArrayOf(0xf8.toByte(), 0xe8.toByte(), 0x50, 0x00)
            else -> null
        }
        if (config != null) {
            decoder = runCatching {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(
                        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(config))
                            setInteger(MediaFormat.KEY_IS_ADTS, 0)
                        },
                        null, null, 0,
                    )
                    start()
                }
            }.getOrNull()
        }
    }

    private fun onFrame(frame: Msg.Frame) {
        when (format) {
            AirPlayAudioCodec.PCM -> write(frame.data, 0, frame.data.size, SAMPLE_RATE, 2)
            AirPlayAudioCodec.AAC_LC, AirPlayAudioCodec.AAC_ELD -> {
                val d = decoder ?: return
                val index = runCatching { d.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
                if (index < 0) return
                val buffer = d.getInputBuffer(index) ?: return
                buffer.clear()
                if (frame.data.size > buffer.capacity()) return
                buffer.put(frame.data)
                d.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
            }
            else -> Unit
        }
    }

    private fun drain() {
        val d = decoder ?: return
        while (true) {
            val index = runCatching { d.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index < 0) continue // format change: picked up from outputFormat below
            val out = d.getOutputBuffer(index)
            if (out != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                out.position(info.offset)
                out.get(bytes)
                val fmt = d.outputFormat
                write(bytes, 0, bytes.size, fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            }
            d.releaseOutputBuffer(index, false)
        }
    }

    private fun write(data: ByteArray, offset: Int, length: Int, rate: Int, channels: Int) {
        val t = trackFor(rate, channels) ?: return
        t.write(data, offset, length)
    }

    private fun trackFor(rate: Int, channels: Int): AudioTrack? {
        if (track != null && rate == trackRate && channels == trackChannels) return track
        track?.let { runCatching { it.stop() }; it.release() }
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(min * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { setVolume(gain); play() }
        }.getOrNull()
        trackRate = rate
        trackChannels = channels
        return track
    }

    private fun releaseAll() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        trackRate = 0
        trackChannels = 0
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val MAX_QUEUED = 50
        const val INPUT_TIMEOUT_US = 5_000L
    }
}
