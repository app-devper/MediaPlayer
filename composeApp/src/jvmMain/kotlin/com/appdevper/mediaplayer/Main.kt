package com.appdevper.mediaplayer

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.appdevper.mediaplayer.di.initKoin
import com.appdevper.mediaplayer.domain.usecase.StopMediaServerUseCase
import com.appdevper.mediaplayer.platform.LocalWindowState
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.koin.mp.KoinPlatform

fun main() {
    initKoin()
    application {
        val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)
        Window(
            onCloseRequest = {
                // Send ssdp:byebye so TVs drop our server immediately.
                runBlocking { KoinPlatform.getKoin().get<StopMediaServerUseCase>()() }
                exitApplication()
            },
            title = "Media Player",
            icon = remember { appIcon() },
            state = windowState,
        ) {
            // Mouse pointer: no 48dp touch-target padding around small buttons (it's for touch screens).
            CompositionLocalProvider(
                LocalWindowState provides windowState,
                LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            ) { App() }
        }
    }
}

private fun appIcon(): BitmapPainter? = object {}.javaClass.getResourceAsStream("/icon.png")?.use {
    BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
}
