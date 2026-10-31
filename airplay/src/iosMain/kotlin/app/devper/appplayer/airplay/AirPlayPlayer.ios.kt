package app.devper.appplayer.airplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Unused: iOS has no receiver ([createAirPlayReceiver] returns null). */
actual class AirPlayPlayer actual constructor() : AirPlayMediaSink {
    actual val videoSize: StateFlow<VideoSize?> = MutableStateFlow(null)
    actual fun setVolume(gain: Float) = Unit
    actual fun release() = Unit
}
