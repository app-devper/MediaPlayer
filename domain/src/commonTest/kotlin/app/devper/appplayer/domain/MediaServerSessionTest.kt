package app.devper.appplayer.domain

import app.devper.appplayer.testing.FakeSettings
import app.devper.appplayer.domain.model.MediaKind
import app.devper.appplayer.domain.model.ServerSettings
import app.devper.appplayer.domain.session.MediaServerSession
import app.devper.appplayer.testing.FakeServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun Settings(autoStart: Boolean) = FakeSettings(ServerSettings("Phone", autoStart, true, true, false, emptyList()))

@OptIn(ExperimentalCoroutinesApi::class)
class MediaServerSessionTest {

    @Test
    fun aRunningServerRestartsOnceWhenWhatItPublishesChanges() = runTest {
        val server = FakeServer()
        val settings = Settings(autoStart = false)
        val session = MediaServerSession(server, settings, backgroundScope)
        session.start()
        runCurrent()
        assertEquals(0, server.starts.size) // not set to start at launch

        settings.update { it.copy(name = "Kitchen") } // changed while stopped: nothing to restart
        runCurrent()
        assertEquals(0, server.starts.size)

        session.turnOn()
        session.update { it.copy(shareImage = true) } // changes what it publishes
        runCurrent()
        assertEquals(2, server.starts.size)
        assertTrue(MediaKind.IMAGE in server.starts.last().sharedKinds)

        settings.update { it.copy(name = "Living Room") } // from anywhere, not only the settings screen
        runCurrent()
        assertEquals("Living Room", server.starts.last().name)

        session.update { it.copy(autoStart = true, receiverEnabled = true) } // doesn't change what it publishes
        runCurrent()
        assertEquals(3, server.starts.size)
    }

    @Test
    fun itStartsAtLaunchWhenSetTo() = runTest {
        val server = FakeServer()
        MediaServerSession(server, Settings(autoStart = true), backgroundScope).start()
        runCurrent()
        assertEquals(1, server.starts.size)
    }
}
