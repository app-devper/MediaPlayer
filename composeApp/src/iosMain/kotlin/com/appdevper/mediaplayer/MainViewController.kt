@file:OptIn(ExperimentalForeignApi::class)

package com.appdevper.mediaplayer

import androidx.compose.ui.window.ComposeUIViewController
import com.appdevper.mediaplayer.di.initKoin
import com.appdevper.mediaplayer.playback.IosBackgroundPlayer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    // Playback category: audio keeps playing with the silent switch on / in background,
    // and AirPlay audio routes are offered.
    runCatching {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }
    // The lock screen and the app lifecycle for the local player, with or without the player screen.
    KoinPlatform.getKoin().get<IosBackgroundPlayer>()
    return ComposeUIViewController { App() }
}
