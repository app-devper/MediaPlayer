@file:Suppress("DEPRECATION")

plugins {
    alias(libs.plugins.mediaplayer.kmp.library)
    alias(libs.plugins.mediaplayer.kmp.compose)
}

// Design system: theme tokens, icon set and building-block components. No app or domain code.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            implementation(libs.coil.compose)
        }
    }
}

android {
    namespace = "app.devper.appplayer.designsystem"
}
