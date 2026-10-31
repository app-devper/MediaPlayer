package app.devper.appplayer.data

import app.devper.appplayer.data.repository.SettingsRepositoryImpl
import app.devper.appplayer.data.upnp.AddressStore
import app.devper.appplayer.data.upnp.ControlPoint
import app.devper.appplayer.data.upnp.SsdpEngine
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {

    @Test
    fun addedAddressesAreStoredAcrossLaunches() {
        val map = MapSettings()
        SettingsRepositoryImpl(map).addressStore.save(listOf("192.168.1.20", "NAS.local"))
        assertEquals(listOf("192.168.1.20", "NAS.local"), SettingsRepositoryImpl(map).addressStore.load())
    }

    @Test
    fun theControlPointKeepsEachAddressOnceAndForgetsItWhateverItsCase() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient()
        try {
            // From an earlier launch, written before addresses were kept once.
            val store = AddressStore.InMemory(listOf("192.168.1.20", " NAS.local", "nas.local", ""))
            val controlPoint = ControlPoint(SsdpEngine(scope), http, scope, store)
            assertEquals(listOf("192.168.1.20", "NAS.local"), controlPoint.addedAddresses.value)

            controlPoint.forget("nas.LOCAL ")
            assertEquals(listOf("192.168.1.20"), controlPoint.addedAddresses.value)
            assertEquals(listOf("192.168.1.20"), store.load()) // kept for the next launch
        } finally {
            http.close()
            scope.cancel()
        }
    }
}
