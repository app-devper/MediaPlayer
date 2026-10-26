package com.appdevper.mediaplayer.airplay

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** [AirPlayReceiver] over UxPlay's library through JNI (Android and Desktop). */
internal class NativeAirPlayReceiver : AirPlayReceiver {
    private val _events = MutableSharedFlow<AirPlayEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AirPlayEvent> = _events.asSharedFlow()

    @Volatile override var sink: AirPlayMediaSink? = null
    @Volatile override var access: AirPlayAccess = object : AirPlayAccess {}
    @Volatile override var video: AirPlayVideoControl? = null
    override val port: Int? get() = NativeAirPlay.port().takeIf { it > 0 }

    // Restarting after a lost connection can't happen on the native thread reporting it (stop joins it).
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "airplay-control").apply { isDaemon = true } }
    @Volatile private var config: AirPlayConfig? = null

    private val listener = object : NativeListener {
        override fun onLog(level: Int, message: String) = Unit
        override fun onConnections(open: Int) = emit(AirPlayEvent.Connections(open))
        override fun admit(deviceId: String, model: String, name: String): Boolean =
            access.admit(deviceId, model, name).also { if (it) emit(AirPlayEvent.ClientConnecting(name, model, deviceId)) }
        override fun onShowPin(pin: String) = emit(AirPlayEvent.ShowPin(pin))
        override fun onRegisterClient(deviceId: String, publicKey: String, name: String) =
            emit(AirPlayEvent.ClientPaired(deviceId, publicKey, name))
        override fun isRegistered(publicKey: String): Boolean = access.isPaired(publicKey)
        override fun onVideoFormat(h265: Boolean, width: Int, height: Int) {
            sink?.onVideoFormat(h265, width, height)
            emit(AirPlayEvent.MirroringStarted(h265, width, height))
        }
        override fun onVideo(data: ByteArray, ptsNs: Long) { sink?.onVideo(data, ptsNs) }
        override fun onVideoStop() {
            sink?.onVideoStop()
            emit(AirPlayEvent.MirroringStopped)
        }
        override fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean) {
            val codec = AirPlayAudioCodec.of(ct)
            sink?.onAudioFormat(codec, samplesPerFrame)
            emit(AirPlayEvent.AudioStarted(codec, samplesPerFrame, usingScreen))
        }
        override fun onAudio(data: ByteArray, ptsNs: Long) { sink?.onAudio(data, ptsNs) }
        override fun onAudioFlush() { sink?.onAudioFlush() }
        override fun onVolume(db: Float) = emit(AirPlayEvent.Volume(db))
        override fun onVideoPlay(url: String, startSeconds: Float) { video?.play(url, startSeconds) }
        override fun onVideoScrub(seconds: Float) { video?.seek(seconds) }
        override fun onVideoRate(rate: Float) { video?.setRate(rate) }
        override fun onVideoPlaybackStop() { video?.stop() }
        override fun videoInfo(): DoubleArray? = video?.info()?.let {
            doubleArrayOf(it.durationSeconds, it.positionSeconds, if (it.playing) 1.0 else 0.0)
        }
        override fun onReset(reason: Int) {
            emit(AirPlayEvent.ConnectionLost(reason))
            control.execute { config?.let { runCatching { startNative(it) } } }
        }
    }

    private fun emit(event: AirPlayEvent) {
        _events.tryEmit(event)
    }

    override fun start(config: AirPlayConfig) {
        this.config = config
        onControlThread { startNative(config) }
    }

    override fun stop() {
        config = null
        onControlThread { NativeAirPlay.stop() }
    }

    private fun onControlThread(block: () -> Unit) {
        try {
            control.submit(block).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun startNative(config: AirPlayConfig) {
        val result = NativeAirPlay.start(
            config.name, config.deviceId, config.keyFile, config.width, config.height, config.fps, config.requirePin, config.video, listener,
        )
        if (result != 0) throw AirPlayException(errorText(result))
    }

    private fun errorText(code: Int) = when (code) {
        -1 -> "Couldn't start network discovery (mDNS port 5353 unavailable?)"
        -4 -> "Couldn't open the AirPlay port"
        -5 -> "Couldn't advertise the AirPlay service"
        else -> "AirPlay receiver failed to start (error $code)"
    }
}

actual fun createAirPlayReceiver(): AirPlayReceiver? = instance

// Native state is process-wide, so there is exactly one receiver.
private val instance: AirPlayReceiver by lazy { NativeAirPlayReceiver() }
