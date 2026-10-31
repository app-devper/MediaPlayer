package app.devper.appplayer.domain

import app.devper.appplayer.testing.FakeEngine
import app.devper.appplayer.testing.FakeRenderer
import app.devper.appplayer.testing.playbackSession
import app.devper.appplayer.domain.model.MediaDevice
import app.devper.appplayer.domain.model.MediaEntry
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.PlaybackTarget
import app.devper.appplayer.domain.model.Receiver
import app.devper.appplayer.domain.model.RepeatMode
import app.devper.appplayer.domain.model.ServerSettings
import app.devper.appplayer.domain.model.TransportState
import app.devper.appplayer.domain.repository.SettingsRepository
import app.devper.appplayer.domain.usecase.FilterEntriesUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertEquals(TransportState.PLAYING, session.state.value.playback.status?.state)
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
        assertEquals(TransportState.STOPPED, session.state.value.playback.status?.state)
    }

    @Test
    fun commandsGoToTheLocalPlayer() = runTest {
        val engine = FakeEngine()
        val renderer = FakeRenderer()
        val session = playbackSession(renderer, backgroundScope, engine)
        session.play(folder[1], folder, autoPlay = false, startPositionMs = 5_000)
        runCurrent()
        assertEquals(TransportState.STOPPED, session.state.value.playback.status?.state)
        session.togglePause()
        runCurrent()
        assertEquals(TransportState.PLAYING, session.state.value.playback.status?.state)
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
        assertEquals(TransportState.STOPPED, session.state.value.playback.status?.state)
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
        assertEquals(30, session.state.value.playback.volume)
        assertEquals(TransportState.PLAYING, session.state.value.playback.status?.state)

        // Track ends on the TV → session moves to the next item and loads it.
        fake.state = TransportState.STOPPED
        advanceTimeBy(150)
        runCurrent()
        assertEquals("a1", session.state.value.current?.id)
        assertTrue("load:a1" in fake.calls)

        session.setVolume(150)
        runCurrent()
        assertEquals(100, session.state.value.playback.volume)
        assertTrue("volume:100" in fake.calls)

        // The same verbs as the local player.
        session.togglePause()
        session.seek(7_000)
        runCurrent()
        assertTrue("pause" in fake.calls)
        assertTrue("seek:7000" in fake.calls)

        // Mute: the renderer goes to 0, and back to its volume.
        session.setMuted(true)
        runCurrent()
        assertTrue(session.state.value.playback.muted)
        session.setMuted(false)
        runCurrent()
        assertEquals(listOf("volume:100", "volume:0", "volume:100"), fake.calls.filter { it.startsWith("volume") })
    }

    @Test
    fun onARendererPreviousAfterTheLastTrackEndedGoesBack() = runTest {
        val fake = FakeRenderer()
        val session = playbackSession(fake, backgroundScope, pollIntervalMs = 100)
        session.setTarget(PlaybackTarget.Renderer(tv))
        session.play(folder[3], folder) // a1, the last
        runCurrent()
        fake.position = 59_000
        advanceTimeBy(150)
        fake.state = TransportState.STOPPED // the TV finished it
        advanceTimeBy(150)
        runCurrent()
        assertEquals(true, session.state.value.playback.status?.ended)
        assertTrue(session.previous())
        runCurrent()
        assertEquals("v1", session.state.value.current?.id)
        assertEquals(listOf("load:a1", "load:v1"), fake.calls.filter { it.startsWith("load") })
    }

    @Test
    fun repeatAllGoesRoundAndRepeatOnePlaysTheSameAgain() = runTest {
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), backgroundScope, engine)
        session.play(folder[3], folder) // a1, the last of v1, a1
        runCurrent()
        assertFalse(session.state.value.hasNext)
        engine.finish()
        advanceTimeBy(300)
        assertEquals("a1", session.state.value.current?.id) // off: it stops there

        session.cycleRepeat()
        assertEquals(RepeatMode.ALL, session.state.value.repeat)
        assertTrue(session.next())
        assertEquals("v1", session.state.value.current?.id) // after the last, the first
        assertTrue(session.previous())
        assertEquals("a1", session.state.value.current?.id) // before the first, the last

        session.cycleRepeat()
        assertEquals(RepeatMode.ONE, session.state.value.repeat)
        runCurrent()
        engine.calls.clear()
        engine.finish()
        advanceTimeBy(300)
        assertEquals("a1", session.state.value.current?.id)
        assertEquals(listOf("load:a1@0"), engine.calls.filter { it.startsWith("load") }) // the same song again
        engine.finish()
        advanceTimeBy(300)
        assertEquals(listOf("load:a1@0", "load:a1@0"), engine.calls.filter { it.startsWith("load") }) // and again
        // Next and previous still move on (only repeat all goes round).
        assertFalse(session.next())
        assertTrue(session.previous())
        assertEquals("v1", session.state.value.current?.id)

        session.cycleRepeat()
        assertEquals(RepeatMode.OFF, session.state.value.repeat)
    }

    @Test
    fun shufflePlaysEveryItemOnceStartingWithTheCurrentOne() = runTest {
        val songs = (1..6).map { entry("s$it", MediaKind.AUDIO) }
        val session = playbackSession(FakeRenderer(), backgroundScope)
        session.play(songs[2], songs)
        session.setShuffle(true)
        val order = session.state.value.order
        assertEquals(2, order.first())
        assertEquals((0..5).toList(), order.sorted())

        val played = mutableListOf(session.state.value.index)
        while (session.next()) played += session.state.value.index
        assertEquals(order, played)
        assertTrue(session.previous())
        assertEquals(order[4], session.state.value.index)

        // Picked in the list while shuffled: the order starts again from it, so the rest still plays.
        session.playAt(order.last())
        assertEquals(order.last(), session.state.value.order.first())
        assertEquals((0..5).toList(), session.state.value.order.sorted())
        assertTrue(session.state.value.hasNext)

        // A new queue while shuffled is shuffled too, from the item picked.
        session.play(songs[0], songs)
        assertEquals(0, session.state.value.order.first())
        session.setShuffle(false)
        assertEquals((0..5).toList(), session.state.value.order)
    }

    @Test
    fun repeatAllOnOneItemPlaysItAgain() = runTest {
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), backgroundScope, engine)
        session.setRepeat(RepeatMode.ALL)
        session.play(folder[3], listOf(folder[3]))
        runCurrent()
        engine.finish()
        advanceTimeBy(300)
        assertEquals(listOf("load:a1@0", "load:a1@0"), engine.calls.filter { it.startsWith("load") })
        session.setShuffle(true) // nothing else to mix with
        assertEquals(listOf(0), session.state.value.order)
    }

    @Test
    fun somethingCastHereEndsWithoutRepeat() = runTest {
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), backgroundScope, engine)
        session.setRepeat(RepeatMode.ONE)
        session.play(folder[1], listOf(folder[1]), castBy = Receiver.DLNA)
        runCurrent()
        engine.calls.clear()
        engine.finish()
        advanceTimeBy(300)
        assertEquals(emptyList(), engine.calls.filter { it.startsWith("load") }) // the sender decides what comes next
        assertEquals(TransportState.STOPPED, session.state.value.playback.status?.state)
    }

    @Test
    fun aCastAfterATvNeverShowsThePreviousItemsStatus() = runTest {
        val engine = FakeEngine()
        val session = playbackSession(FakeRenderer(), backgroundScope, engine)
        session.play(folder[1], folder)
        runCurrent()
        engine.facts = engine.facts.copy(positionMs = 42_000) // the old item, well into it
        advanceTimeBy(1_100)
        session.setTarget(PlaybackTarget.Renderer(tv))
        runCurrent()

        val seen = mutableListOf<Long?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.state.collect { seen += it.playback.status?.positionMs } }
        // A DLNA controller casts here: back to this device, a new item.
        session.setTarget(PlaybackTarget.Local)
        session.play(folder[3], listOf(folder[3]), castBy = Receiver.DLNA)
        runCurrent()
        assertTrue(42_000L !in seen.drop(1), "the old item's status showed: $seen")
        assertEquals(0, session.state.value.playback.status?.positionMs)
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
        assertEquals(TransportState.PLAYING, session.state.value.playback.status?.state) // the item plays here now
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
}

