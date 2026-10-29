package com.appdevper.mediaplayer.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/** AirPlay audio → AudioTrack: AAC-LC/AAC-ELD through MediaCodec, PCM straight through. */
internal fun audioOutput() = AudioThread(AudioPipeline(MediaCodecAac::open, AudioTrackSink()))

/** AAC through MediaCodec: its output comes some calls after its input ([drain]). */
private class MediaCodecAac(private val codec: MediaCodec) : AacDecoder {
    private val info = MediaCodec.BufferInfo()

    override fun send(frame: ByteArray, ptsUs: Long) {
        val index = runCatching { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        if (frame.size > buffer.capacity()) return
        buffer.put(frame)
        codec.queueInputBuffer(index, 0, frame.size, ptsUs, 0)
    }

    override fun drain(out: (ByteArray, Int, Int) -> Unit) {
        while (true) {
            val index = runCatching { codec.dequeueOutputBuffer(info, 0) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (index < 0) continue // format change: picked up from outputFormat below
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                val fmt = codec.outputFormat
                out(bytes, fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    override fun flush() {
        runCatching { codec.flush() }
    }

    override fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        const val INPUT_TIMEOUT_US = 5_000L

        fun open(config: ByteArray): AacDecoder? = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioPipeline.SAMPLE_RATE, 2).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(config))
                        setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    },
                    null, null, 0,
                )
                start()
            }
        }.getOrNull()?.let(::MediaCodecAac)
    }
}

/** An AudioTrack, made again when the rate or channels change. */
private class AudioTrackSink : PcmSink {
    @Volatile private var gain = 1f
    @Volatile private var track: AudioTrack? = null
    private var rate = 0
    private var channels = 0

    override fun write(pcm: ByteArray, rate: Int, channels: Int) {
        trackFor(rate, channels)?.write(pcm, 0, pcm.size)
    }

    override fun flush() {
        track?.let { it.pause(); it.flush(); it.play() }
    }

    override fun setVolume(gain: Float) {
        this.gain = gain
        track?.setVolume(gain)
    }

    override fun release() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        rate = 0
        channels = 0
    }

    private fun trackFor(rate: Int, channels: Int): AudioTrack? {
        if (track != null && rate == this.rate && channels == this.channels) return track
        release()
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
        this.rate = rate
        this.channels = channels
        return track
    }
}
