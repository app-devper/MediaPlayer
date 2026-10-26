package com.appdevper.mediaplayer.data.repository

import com.appdevper.mediaplayer.airplay.AirPlayAccess
import com.appdevper.mediaplayer.airplay.AirPlayConfig
import com.appdevper.mediaplayer.airplay.AirPlayEvent
import com.appdevper.mediaplayer.airplay.AirPlayPlayer
import com.appdevper.mediaplayer.airplay.AirPlayReceiver
import com.appdevper.mediaplayer.airplay.airPlayGain
import com.appdevper.mediaplayer.data.platform.AppStorage
import com.appdevper.mediaplayer.data.platform.MulticastLock
import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.repository.AirPlayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [AirPlayRepository] over the :airplay receiver; [receiver] is null where AirPlay isn't available. */
class AirPlayRepositoryImpl(
    private val receiver: AirPlayReceiver?,
    private val player: AirPlayPlayer,
    private val settings: SettingsRepositoryImpl,
    private val storage: AppStorage,
    private val multicast: MulticastLock,
    scope: CoroutineScope,
) : AirPlayRepository {

    private val _status = MutableStateFlow(if (receiver == null) AirPlayStatus.Unsupported else AirPlayStatus.Stopped)
    override val status: StateFlow<AirPlayStatus> = _status.asStateFlow()

    private val _activity = MutableStateFlow(AirPlayActivity())
    override val activity: StateFlow<AirPlayActivity> = _activity.asStateFlow()

    private val mutex = Mutex()

    init {
        receiver?.let { r ->
            r.sink = player
            r.access = object : AirPlayAccess {
                override fun isPaired(publicKey: String) = publicKey in settings.airPlayPairedKeys
            }
            scope.launch { r.events.collect(::onEvent) }
        }
    }

    override suspend fun start(name: String, requirePin: Boolean) {
        val r = receiver ?: return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // UxPlay's mDNS responder needs multicast (Android filters it without this lock).
                multicast.acquire()
                try {
                    r.start(AirPlayConfig(name, settings.airPlayDeviceId, "${storage.dir}/airplay_key.pem", requirePin = requirePin))
                    _status.value = AirPlayStatus.Running(name)
                } catch (e: Throwable) {
                    runCatching { r.stop() }
                    multicast.release()
                    // UnsatisfiedLinkError included: a desktop build without the native library for this OS.
                    _status.value = AirPlayStatus.Failed(e.message ?: e::class.simpleName ?: "AirPlay failed to start")
                }
            }
            _activity.value = AirPlayActivity()
        }
    }

    override suspend fun stop() {
        val r = receiver ?: return
        mutex.withLock {
            withContext(Dispatchers.IO) { runCatching { r.stop() } }
            multicast.release()
            _status.value = AirPlayStatus.Stopped
            _activity.value = AirPlayActivity()
        }
    }

    private fun onEvent(event: AirPlayEvent) {
        when (event) {
            is AirPlayEvent.Connections -> if (event.open == 0) _activity.value = AirPlayActivity()
            is AirPlayEvent.ClientConnecting -> _activity.update { it.copy(client = event.name) }
            is AirPlayEvent.ShowPin -> _activity.update { it.copy(pin = event.pin) }
            is AirPlayEvent.ClientPaired -> {
                settings.addAirPlayPairedKey(event.publicKey)
                _activity.update { it.copy(pin = null) }
            }
            is AirPlayEvent.MirroringStarted -> _activity.update { it.copy(mirroring = true, audioOnly = false, pin = null) }
            AirPlayEvent.MirroringStopped -> _activity.update { it.copy(mirroring = false) }
            is AirPlayEvent.AudioStarted -> _activity.update { it.copy(audioOnly = !event.withScreen && !it.mirroring, pin = null) }
            is AirPlayEvent.Volume -> player.setVolume(airPlayGain(event.db))
            is AirPlayEvent.ConnectionLost -> _activity.value = AirPlayActivity()
        }
    }
}
