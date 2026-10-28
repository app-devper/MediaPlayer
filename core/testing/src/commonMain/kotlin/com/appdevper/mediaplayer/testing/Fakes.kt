package com.appdevper.mediaplayer.testing

import com.appdevper.mediaplayer.domain.model.AirPlayActivity
import com.appdevper.mediaplayer.domain.model.AirPlayStatus
import com.appdevper.mediaplayer.domain.model.CastCommand
import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.ReceiverPlayback
import com.appdevper.mediaplayer.domain.model.ReceiverStatus
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.SenderCommand
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.repository.AirPlayRepository
import com.appdevper.mediaplayer.domain.repository.CastReceiverRepository
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SubtitleRepository
import com.appdevper.mediaplayer.domain.session.EngineFacts
import com.appdevper.mediaplayer.domain.session.LocalPlayer
import com.appdevper.mediaplayer.domain.session.PlaybackEngine
import com.appdevper.mediaplayer.domain.session.PlaybackSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** A platform player that does what it's told at once; [facts] can be set to anything. */
class FakeEngine(private val durationMs: Long = 60_000) : PlaybackEngine {
    val calls = mutableListOf<String>()
    var facts = EngineFacts()
    var gain = 1f

    override fun load(entry: MediaEntry, url: String, playWhenReady: Boolean, startPositionMs: Long) {
        calls += "load:${entry.id}@$startPositionMs"
        facts = EngineFacts(playing = playWhenReady, positionMs = startPositionMs, durationMs = durationMs)
    }

    override fun unload() {
        calls += "unload"
        facts = EngineFacts()
    }

    override fun play() {
        calls += "play"
        facts = facts.copy(playing = true)
    }

    override fun pause() {
        calls += "pause"
        facts = facts.copy(playing = false)
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek:$positionMs"
        facts = facts.copy(positionMs = positionMs, ended = false)
    }

    override fun setVolume(volume: Float) {
        gain = volume
    }

    override fun reload(positionMs: Long) {
        calls += "reload:$positionMs"
        facts = facts.copy(error = null, positionMs = positionMs)
    }

    override fun selectTextTrack(id: String?) {
        calls += "track:$id"
        facts = facts.copy(selectedTextTrack = id)
    }

    override fun facts() = facts

    /** Plays to the end by itself. */
    fun finish() {
        facts = facts.copy(ended = true, positionMs = facts.durationMs)
    }
}

/** A DLNA renderer that takes every command (listed in [calls]) and reports what it's set to. */
class FakeRenderer(
    var state: TransportState = TransportState.PLAYING,
    var position: Long = 1_000,
    var duration: Long = 60_000,
    private val volume: Int = 30,
) : RendererRepository {
    val calls = mutableListOf<String>()
    override suspend fun load(device: MediaDevice, entry: MediaEntry) {
        calls += "load:${entry.id}"
        state = TransportState.PLAYING
    }
    override suspend fun play(device: MediaDevice) { calls += "play" }
    override suspend fun pause(device: MediaDevice) { calls += "pause" }
    override suspend fun stop(device: MediaDevice) { calls += "stop" }
    override suspend fun seek(device: MediaDevice, positionMs: Long) { calls += "seek:$positionMs" }
    override suspend fun status(device: MediaDevice) = RendererStatus(state, position, duration)
    override suspend fun volume(device: MediaDevice) = volume
    override suspend fun setVolume(device: MediaDevice, volume: Int) { calls += "volume:$volume" }
}

/** The DLNA receiver: [commands] come in as a controller would send them; what it's told is kept. */
class FakeReceiver : CastReceiverRepository {
    val calls = mutableListOf<String>()
    val published = mutableListOf<ReceiverPlayback>()
    override val status = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Stopped)
    override val commands = MutableSharedFlow<CastCommand>()
    override suspend fun start(name: String) {
        calls += "start:$name"
        status.value = ReceiverStatus.Running(name)
    }
    override suspend fun stop() {
        calls += "stop"
        status.value = ReceiverStatus.Stopped
    }
    override fun publish(playback: ReceiverPlayback) { published += playback }
}

/** The AirPlay receiver ([AirPlayStatus.Unsupported] where the platform has none). */
class FakeAirPlay(status: AirPlayStatus = AirPlayStatus.Stopped) : AirPlayRepository {
    val calls = mutableListOf<String>()
    val published = mutableListOf<ReceiverPlayback>()
    val sentToSender = mutableListOf<SenderCommand>()
    override val status = MutableStateFlow(status)
    override val activity = MutableStateFlow(AirPlayActivity())
    override val commands = MutableSharedFlow<CastCommand>()
    override fun publish(playback: ReceiverPlayback) { published += playback }
    override suspend fun sendToSender(command: SenderCommand): Boolean {
        sentToSender += command
        return true
    }
    override suspend fun start(name: String, requirePin: Boolean) {
        calls += "start:$name:pin=$requirePin"
        status.value = AirPlayStatus.Running(name)
    }
    override suspend fun stop() {
        calls += "stop"
        if (status.value != AirPlayStatus.Unsupported) status.value = AirPlayStatus.Stopped
    }
}

/** Subtitle files: one Thai line from 1 s to 2 s; a URL with "broken" fails, one with "empty" has no lines. */
class FakeSubtitles : SubtitleRepository {
    override suspend fun fetch(url: String): ByteArray = when {
        "broken" in url -> throw IllegalStateException("404")
        "empty" in url -> "nothing here".encodeToByteArray()
        else -> "1\n00:00:01,000 --> 00:00:02,000\nสวัสดี\n".encodeToByteArray()
    }
}

/** A playback session on [engine], subtitle files from [FakeSubtitles] (read at once, not on another thread). */
fun playbackSession(
    renderer: RendererRepository,
    scope: CoroutineScope,
    engine: FakeEngine = FakeEngine(),
    pollIntervalMs: Long = 1_000,
) = PlaybackSession(renderer, LocalPlayer(engine, scope, subtitleFiles = FakeSubtitles(), subtitleParsing = Dispatchers.Unconfined), scope, pollIntervalMs)
