package com.appdevper.mediaplayer.airplay

import kotlinx.coroutines.flow.SharedFlow

/** What a sender's AirPlay menu shows and how mirroring should be sized. */
class AirPlayConfig(
    val name: String,
    /** Stable 6-byte device id: senders remember receivers by it. */
    val deviceId: ByteArray,
    /** File that keeps the receiver's Ed25519 identity, so paired senders stay paired. */
    val keyFile: String,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    /** A sender's first connection needs the PIN shown on this screen. */
    val requirePin: Boolean = false,
    /** Accept AirPlay video (YouTube and other apps' AirPlay button), played via [AirPlayReceiver.video]. */
    val video: Boolean = true,
) {
    init {
        require(deviceId.size == 6) { "deviceId must be 6 bytes" }
    }
}

/** Audio compression types a sender uses ("ct"). */
enum class AirPlayAudioCodec(val ct: Int) {
    PCM(1), ALAC(2), AAC_LC(4), AAC_ELD(8);

    companion object {
        fun of(ct: Int): AirPlayAudioCodec? = entries.firstOrNull { it.ct == ct }
    }
}

sealed interface AirPlayEvent {
    data class Connections(val open: Int) : AirPlayEvent
    data class ClientConnecting(val name: String, val model: String, val deviceId: String) : AirPlayEvent
    data class ShowPin(val pin: String) : AirPlayEvent
    data class ClientPaired(val deviceId: String, val publicKey: String, val name: String) : AirPlayEvent
    data class MirroringStarted(val h265: Boolean, val width: Int, val height: Int) : AirPlayEvent
    data object MirroringStopped : AirPlayEvent
    data class AudioStarted(val codec: AirPlayAudioCodec?, val samplesPerFrame: Int, val withScreen: Boolean) : AirPlayEvent

    /** The sender ended its audio stream (it may stay connected, e.g. to play video next). */
    data object AudioStopped : AirPlayEvent
    /** AirPlay audio "now playing": what the sender says is playing. */
    data class Track(val info: AirPlayTrackInfo) : AirPlayEvent
    /** Cover art (JPEG/PNG) for the current song; null = none. */
    class Artwork(val image: ByteArray?) : AirPlayEvent
    data class Progress(val positionMs: Long, val durationMs: Long) : AirPlayEvent
    /** AirPlay volume: -30 dB (min) .. 0 dB (max); muted is -144. */
    data class Volume(val db: Float) : AirPlayEvent
    /** The connection broke; the receiver restarts itself. */
    data class ConnectionLost(val reason: Int) : AirPlayEvent
}

/** Gets the decrypted streams. Called on network threads: hand off quickly, never block. */
interface AirPlayMediaSink {
    fun onVideoFormat(h265: Boolean, width: Int, height: Int) {}
    /** H.264/H.265 Annex-B access unit (start codes included; SPS/PPS precede keyframes). */
    fun onVideo(data: ByteArray, ptsNs: Long) {}
    fun onVideoStop() {}
    fun onAudioFormat(codec: AirPlayAudioCodec?, samplesPerFrame: Int) {}
    /** One encoded audio frame of the codec from [onAudioFormat]. */
    fun onAudio(data: ByteArray, ptsNs: Long) {}
    fun onAudioFlush() {}
}

/** Where the app's player stands, for the sender's progress bar. [durationSeconds] < 0 = finished. */
data class AirPlayVideoInfo(val durationSeconds: Double, val positionSeconds: Double, val playing: Boolean)

/**
 * AirPlay video ("play this URL", e.g. YouTube): the sender sends an http(s) URL, or the receiver's
 * own local HLS address for YouTube, and then controls playback. Called on network threads.
 */
interface AirPlayVideoControl {
    fun play(url: String, startSeconds: Float)
    fun seek(seconds: Float)
    /** 0 = pause, 1 = play. */
    fun setRate(rate: Float)
    fun stop()
    /** Must answer immediately (cached state); null while nothing is loaded. */
    fun info(): AirPlayVideoInfo?
}

/** Which senders may connect and which have paired before (for PIN mode). */
interface AirPlayAccess {
    fun admit(deviceId: String, model: String, name: String): Boolean = true
    fun isPaired(publicKey: String): Boolean = false
}

/** An AirPlay 2 receiver (screen mirroring + audio). One per process. */
interface AirPlayReceiver {
    val events: SharedFlow<AirPlayEvent>
    var sink: AirPlayMediaSink?
    var access: AirPlayAccess
    var video: AirPlayVideoControl?

    /** The port senders connect to while running (advertised over mDNS), else null. */
    val port: Int?

    /** Throws [AirPlayException] when the receiver can't start (no network, port in use…). */
    fun start(config: AirPlayConfig)
    fun stop()
}

class AirPlayException(message: String) : Exception(message)

/** The platform's receiver, or null where AirPlay receiving isn't available (iOS). */
expect fun createAirPlayReceiver(): AirPlayReceiver?
