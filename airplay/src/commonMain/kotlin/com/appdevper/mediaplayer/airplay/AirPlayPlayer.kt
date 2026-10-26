package com.appdevper.mediaplayer.airplay

import kotlinx.coroutines.flow.StateFlow

data class VideoSize(val width: Int, val height: Int)

/**
 * Plays what an [AirPlayReceiver] receives: mirroring video and audio. Set it as the receiver's
 * [AirPlayReceiver.sink]. Platforms add their video output (an Android Surface, desktop frames).
 */
expect class AirPlayPlayer() : AirPlayMediaSink {
    /** Decoded picture size once known (changes when the sender rotates). */
    val videoSize: StateFlow<VideoSize?>

    /** 0..1, e.g. from [airPlayGain]. */
    fun setVolume(gain: Float)

    fun release()
}
