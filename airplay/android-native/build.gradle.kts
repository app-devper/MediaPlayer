// The AirPlay receiver's native library for Android (UxPlay + BoringSSL + libplist, see
// ../native/CMakeLists.txt). AGP's KMP library plugin has no externalNativeBuild, so the CMake
// build lives in this plain Android library, which :airplay's androidMain depends on; the .so
// ends up in the app like before.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "app.devper.appplayer.airplay.nativelib"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()
    enableKotlin = false

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DAIRPLAY_BUILD_PROBE=OFF", "-DANDROID_STL=c++_static") } }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}
