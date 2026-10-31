package app.devper.appplayer.airplay

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class AirPlayPlayer actual constructor() : AirPlayMediaSink {
    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    actual val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()

    private val video = H264Decoder { w, h -> _videoSize.value = VideoSize(w, h) }
    private val audio = audioOutput()

    /** Where mirroring is drawn; null while nothing shows it (decoding pauses, the stream is kept). */
    fun setSurface(surface: Surface?) {
        video.setSurface(surface)
    }

    override fun onVideoFormat(h265: Boolean, width: Int, height: Int) {
        if (_videoSize.value == null) _videoSize.value = VideoSize(width, height)
    }

    override fun onVideo(data: ByteArray, ptsNs: Long) = video.decode(data, ptsNs)

    override fun onVideoStop() {
        video.reset()
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
