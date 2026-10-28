package com.appdevper.mediaplayer.data

import com.appdevper.mediaplayer.data.repository.SettingsRepositoryImpl
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {

    @Test
    fun savedAddressesAreKeptOnceAndCanBeRemoved() {
        val settings = SettingsRepositoryImpl(MapSettings())
        settings.addServerAddress("192.168.1.20")
        settings.addServerAddress("NAS.local")
        settings.addServerAddress("nas.local") // the same, in another case
        assertEquals(listOf("192.168.1.20", "NAS.local"), settings.serverAddresses)
        settings.removeServerAddress("nas.LOCAL")
        assertEquals(listOf("192.168.1.20"), settings.serverAddresses)
    }
}
