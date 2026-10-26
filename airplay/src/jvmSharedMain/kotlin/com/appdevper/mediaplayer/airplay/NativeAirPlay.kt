package com.appdevper.mediaplayer.airplay

/** JNI entry points of libmediaplayer_airplay (native/src/airplay_jni.c). */
internal object NativeAirPlay {
    init {
        loadNativeLibrary()
    }

    external fun start(
        name: String,
        hwAddr: ByteArray,
        keyFile: String,
        width: Int,
        height: Int,
        fps: Int,
        requirePin: Boolean,
        listener: NativeListener,
    ): Int

    /** Port senders connect to, 0 when stopped. */
    external fun port(): Int

    /** Joins the native threads: no callback runs after it returns. */
    external fun stop()
}

/** Loads libmediaplayer_airplay for this platform. */
internal expect fun loadNativeLibrary()

/** Called from native code; method names and signatures must match airplay_jni.c. */
internal interface NativeListener {
    fun onLog(level: Int, message: String)
    fun onConnections(open: Int)
    fun admit(deviceId: String, model: String, name: String): Boolean
    fun onShowPin(pin: String)
    fun onRegisterClient(deviceId: String, publicKey: String, name: String)
    fun isRegistered(publicKey: String): Boolean
    fun onVideoFormat(h265: Boolean, width: Int, height: Int)
    fun onVideo(data: ByteArray, ptsNs: Long)
    fun onVideoStop()
    fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean)
    fun onAudio(data: ByteArray, ptsNs: Long)
    fun onAudioFlush()
    fun onVolume(db: Float)
    fun onReset(reason: Int)
}
