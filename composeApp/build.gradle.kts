@file:Suppress("DEPRECATION")

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.mediaplayer.kmp.application)
    alias(libs.plugins.mediaplayer.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":data"))
            implementation(project(":core:designsystem"))
            implementation(project(":airplay"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.ktor.client.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.exoplayer.hls) // AirPlay video (YouTube) is HLS
            implementation(libs.media3.session) // background playback: notification, lock screen, headset
            implementation(libs.androidx.lifecycle.process) // videos pause while the app is in the background
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }

        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
            implementation(project(":core:testing"))
        }
    }
}

android {
    namespace = "com.appdevper.mediaplayer"
    defaultConfig.applicationId = "com.appdevper.appplayer"

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.appdevper.mediaplayer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // jdeps' suggestion plus what is loaded at runtime: Ktor's Java client (java.net.http)
            // and TLS with EC certificates.
            modules("java.instrument", "java.management", "java.prefs", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            packageName = "Media Player"
            packageVersion = libs.versions.app.version.get()
            description = "DLNA / UPnP media player and media server"
            vendor = "appdevper"
            macOS {
                bundleID = "com.appdevper.mediaplayer"
                iconFile.set(project.file("icons/icon.icns"))
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "Media Player"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}
