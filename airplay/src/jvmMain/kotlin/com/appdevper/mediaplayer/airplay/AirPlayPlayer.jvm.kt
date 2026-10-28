package com.appdevper.mediaplayer.airplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One decoded mirroring picture, BGRA (Skia's BGRA_8888), rows packed. */
class VideoFrame(val width: Int, val height: Int, val bgra: ByteArray)

actual class AirPlayPlayer actual constructor() : AirPlayMediaSink {
    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    actual val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()

    private val _frames = MutableStateFlow<VideoFrame?>(null)

    /** Latest picture; the UI draws whatever is newest (older ones are simply skipped). */
    val frames: StateFlow<VideoFrame?> = _frames.asStateFlow()

    private val video = FfmpegVideoDecoder { frame ->
        if (_videoSize.value?.let { it.width != frame.width || it.height != frame.height } != false) {
            _videoSize.value = VideoSize(frame.width, frame.height)
        }
        _frames.value = frame
    }
    private val audio = jvmAudioOutput()

    override fun onVideoFormat(h265: Boolean, width: Int, height: Int) {
        if (_videoSize.value == null) _videoSize.value = VideoSize(width, height)
    }

    override fun onVideo(data: ByteArray, ptsNs: Long) = video.decode(data)

    override fun onVideoStop() {
        video.reset()
        _frames.value = null
        _videoSize.value = null
    }

    override fun onAudioFormat(codec: AirPlayAudioCodec?, samplesPerFrame: Int) {
        audio.setFormat(codec)
    }

    override fun onAudio(data: ByteArray, ptsNs: Long) = audio.play(data, ptsNs)

    override fun onAudioFlush() {
        audio.flush()
    }

    actual fun setVolume(gain: Float) = audio.setVolume(gain)

    actual fun release() {
        video.release()
        audio.release()
    }
}
