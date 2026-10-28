package com.appdevper.mediaplayer.domain

import com.appdevper.mediaplayer.domain.model.MediaDevice
import com.appdevper.mediaplayer.domain.model.MediaEntry
import com.appdevper.mediaplayer.domain.model.MediaKind
import com.appdevper.mediaplayer.domain.model.PlaybackTarget
import com.appdevper.mediaplayer.domain.model.RendererStatus
import com.appdevper.mediaplayer.domain.model.ServerSettings
import com.appdevper.mediaplayer.domain.model.TransportState
import com.appdevper.mediaplayer.domain.repository.RendererRepository
import com.appdevper.mediaplayer.domain.repository.SettingsRepository
import com.appdevper.mediaplayer.domain.usecase.FilterEntriesUseCase
import com.appdevper.mediaplayer.domain.usecase.UpdateServerSettingsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeRenderer : RendererRepository {
    val calls = mutableListOf<String>()
    var state = TransportState.PLAYING
    override suspend fun load(device: MediaDevice, entry: MediaEntry) { calls += "load:${entry.id}"; state = TransportState.PLAYING }
    override suspend fun play(device: MediaDevice) { calls += "play" }
    override suspend fun pause(device: MediaDevice) { calls += "pause" }
    override suspend fun stop(device: MediaDevice) { calls += "stop" }
    override suspend fun seek(device: MediaDevice, positionMs: Long) { calls += "seek:$positionMs" }
    override suspend fun status(device: MediaDevice) = RendererStatus(state, 1_000, 60_000)
    override suspend fun volume(device: MediaDevice) = 30
    override suspend fun setVolume(device: MediaDevice, volume: Int) { calls += "volume:$volume" }
}

private fun entry(id: String, kind: MediaKind) =
    MediaEntry(id = id, parentId = "0", title = id, kind = kind, streamUrl = if (kind == MediaKind.FOLDER) null else "http://h/$id")

private val tv = MediaDevice("uuid:tv", "TV", "", "", "10.0.0.2", null, isServer = false, isRenderer = true)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionTest {

    private val folder = listOf(
        entry("dir", MediaKind.FOLDER),
        entry("v1", MediaKind.VIDEO),
        entry("p1", MediaKind.IMAGE),
        entry("a1", MediaKind.AUDIO),
        entry("p2", MediaKind.IMAGE),
    )

    @Test
    fun queueKeepsSameFamily() = runTest {
        val session = playbackSession(FakeRenderer(), backgroundScope)
        session.play(folder[3], folder)
        assertEquals(listOf("v1", "a1"), session.state.value.queue.map { it.id })
        assertEquals(1, session.state.value.index)

        session.play(folder[4], folder)
        assertEquals(listOf("p1", "p2"), session.state.value.queue.map { it.id })
        assertEquals("p2", session.state.value.current?.id)
        assertFalse(session.state.value.hasNext)
        assertTrue(session.previous())
        assertEquals("p1", session.state.value.current?.id)
    }

    @Test
    fun playingTheSameItemAgainLoadsItAgain() = runTest {
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), backgroundScope, engine)
        session.play(folder[3], folder)
        session.play(folder[3], folder)
        session.playAt(session.state.value.index)
        runCurrent()
        assertEquals(listOf("load:a1@0", "load:a1@0", "load:a1@0"), engine.calls)
    }

    @Test
    fun theLocalPlayerMovesOnAtTheEnd() = runTest {
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), backgroundScope, engine)
        session.play(folder[1], folder)
        runCurrent()
        assertEquals(TransportState.PLAYING, session.state.value.local?.state)
        engine.finish()
        advanceTimeBy(300)
        runCurrent()
        assertEquals("a1", session.state.value.current?.id)
        assertEquals("load:a1@0", engine.calls.last())

        // The last one ends: it stays there, stopped.
        engine.finish()
        advanceTimeBy(300)
        runCurrent()
        assertEquals("a1", session.state.value.current?.id)
        assertEquals(TransportState.STOPPED, session.state.value.local?.state)
    }

    @Test
    fun commandsGoToTheLocalPlayer() = runTest {
        val engine = FakeEngine()
        val renderer = FakeRenderer()
        val session = playbackSession(renderer, backgroundScope, engine)
        session.play(folder[1], folder, autoPlay = false, startPositionMs = 5_000)
        runCurrent()
        assertEquals(TransportState.STOPPED, session.state.value.local?.state)
        session.togglePause()
        runCurrent()
        assertEquals(TransportState.PLAYING, session.state.value.local?.state)
        session.togglePause()
        session.seek(20_000)
        session.setVolume(40)
        session.setMuted(true)
        runCurrent()
        assertEquals(listOf("load:v1@5000", "play", "pause", "seek:20000"), engine.calls)
        assertEquals(0f, engine.gain)
        session.setMuted(false)
        runCurrent()
        assertEquals(0.4f, engine.gain)

        session.stop()
        runCurrent()
        assertEquals(TransportState.STOPPED, session.state.value.local?.state)
        assertFalse(session.state.value.autoPlay) // loaded again later, it waits for Play
        assertEquals(emptyList(), renderer.calls) // nothing went to a renderer
    }

    @Test
    fun previousRestartsAfterThreeSeconds() = runTest {
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), backgroundScope, engine)
        session.play(folder[3], folder)
        runCurrent()
        session.seek(10_000)
        runCurrent()
        assertTrue(session.previous())
        runCurrent()
        assertEquals("a1", session.state.value.current?.id) // same item, from the start
        assertEquals("seek:0", engine.calls.last())

        assertTrue(session.previous())
        runCurrent()
        assertEquals("v1", session.state.value.current?.id)
        assertFalse(session.previous()) // the first: back to its start
    }

    @Test
    fun rendererLoadsAndAutoAdvances() = runTest {
        val fake = FakeRenderer()
        val session = playbackSession(fake, backgroundScope, pollIntervalMs = 100)
        session.setTarget(PlaybackTarget.Renderer(tv))
        session.play(folder[1], folder)
        runCurrent()
        assertEquals(listOf("load:v1"), fake.calls)
        assertEquals(30, session.state.value.remote.volume)
        assertEquals(TransportState.PLAYING, session.state.value.remote.status?.state)

        // Track ends on the TV → session moves to the next item and loads it.
        fake.state = TransportState.STOPPED
        advanceTimeBy(150)
        runCurrent()
        assertEquals("a1", session.state.value.current?.id)
        assertTrue("load:a1" in fake.calls)

        session.setVolume(150)
        runCurrent()
        assertEquals(100, session.state.value.remote.volume)
        assertTrue("volume:100" in fake.calls)

        // The same verbs as the local player.
        session.togglePause()
        session.seek(7_000)
        runCurrent()
        assertTrue("pause" in fake.calls)
        assertTrue("seek:7000" in fake.calls)
    }

    @Test
    fun switchingBackToLocalStopsTheRenderer() = runTest {
        val fake = FakeRenderer()
        val session = playbackSession(fake, backgroundScope)
        session.setTarget(PlaybackTarget.Renderer(tv))
        session.play(folder[1], folder)
        runCurrent()
        session.setTarget(PlaybackTarget.Local)
        runCurrent()
        assertTrue("stop" in fake.calls)
        assertEquals(PlaybackTarget.Local, session.state.value.target)
        assertEquals(TransportState.PLAYING, session.state.value.local?.state) // the item plays here now
    }
}

class UseCaseTest {
    @Test
    fun filterMatchesTitleArtistAlbum() {
        val list = listOf(
            MediaEntry("1", "0", "Holiday clip", MediaKind.VIDEO),
            MediaEntry("2", "0", "Song", MediaKind.AUDIO, artist = "Bodyslam"),
            MediaEntry("3", "0", "Other", MediaKind.AUDIO, album = "Holiday hits"),
        )
        assertEquals(listOf("1", "3"), FilterEntriesUseCase()(list, " holiday ").map { it.id })
        assertEquals(listOf("2"), FilterEntriesUseCase()(list, "body").map { it.id })
        assertEquals(3, FilterEntriesUseCase()(list, "").size)
    }

    @Test
    fun settingsUpdateReportsWhetherRestartIsNeeded() {
        val repo = object : SettingsRepository {
            val flow = MutableStateFlow(ServerSettings("A", false, true, true, true, emptyList()))
            override val serverSettings: StateFlow<ServerSettings> = flow
            override fun update(transform: (ServerSettings) -> ServerSettings) = flow.update(transform)
        }
        val update = UpdateServerSettingsUseCase(repo)
        assertFalse(update { it.copy(autoStart = true) })
        assertTrue(update { it.copy(shareImage = false) })
        assertTrue(update { it.copy(name = "B") })
    }
}

