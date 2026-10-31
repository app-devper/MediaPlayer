import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.util.Properties

plugins {
    alias(libs.plugins.mediaplayer.kmp.library)
}

val hostPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
    when {
        "mac" in os -> "macos-$arch"
        "win" in os -> "windows-$arch"
        else -> "linux-$arch"
    }
}

/** JavaCPP classifier for the build machine (desktop builds bundle only its natives). */
val bytedecoPlatform: String = when (hostPlatform) {
    "macos-arm64" -> "macosx-arm64"
    "macos-x64" -> "macosx-x86_64"
    "windows-x64" -> "windows-x86_64"
    "linux-arm64" -> "linux-arm64"
    else -> "linux-x86_64"
}

// AirPlay receiver (screen mirroring + audio): UxPlay's GPL-3.0 protocol library in native/,
// built with CMake for Android (AGP externalNativeBuild) and for the desktop build machine.
kotlin {
    // "jvmShared": the JNI binding, shared by Android and Desktop.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        // On-device tests of the MediaCodec pipeline (./gradlew :airplay:connectedDebugAndroidTest).
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            // FFmpeg decodes mirroring video and AAC on the desktop; natives only for the build machine.
            // api: the desktop app's own media player uses FFmpeg too.
            api(libs.bytedeco.ffmpeg)
            api(libs.bytedeco.javacpp)
            runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.bytedeco.ffmpeg.get()}:$bytedecoPlatform")
            runtimeOnly("org.bytedeco:javacpp:${libs.versions.bytedeco.javacpp.get()}:$bytedecoPlatform")
        }
    }
}

android {
    namespace = "app.devper.appplayer.airplay"
    ndkVersion = libs.versions.android.ndk.get()
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += listOf("-DAIRPLAY_BUILD_PROBE=OFF", "-DANDROID_STL=c++_static") } }
    }
    externalNativeBuild {
        cmake {
            path = file("native/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

// --- Desktop: build the JNI library for this machine and bundle it as a resource -------------

val nativeSrc = layout.projectDirectory.dir("native")
val hostBuildDir = layout.buildDirectory.dir("native/host")
val hostResourcesDir = layout.buildDirectory.dir("generated/airplay/resources")

/** cmake/ninja from PATH, else the Android SDK's (which AGP installs for the Android build anyway). */
fun sdkTool(name: String): String {
    val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }
    if (onPath != null) return onPath.absolutePath
    val local = rootProject.file("local.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    val sdk = local?.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("cmake not found: install it or set sdk.dir / ANDROID_HOME")
    return "$sdk/cmake/${libs.versions.cmake.get()}/bin/$name"
}

val javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }.map { it.metadata.installationPath.asFile.absolutePath }

val configureNativeHost by tasks.registering(Exec::class) {
    inputs.file(nativeSrc.file("CMakeLists.txt"))
    outputs.file(hostBuildDir.map { it.file("CMakeCache.txt") })
    environment("JAVA_HOME", javaHome.get())
    commandLine(
        sdkTool("cmake"), "-S", nativeSrc.asFile.absolutePath, "-B", hostBuildDir.get().asFile.absolutePath,
        "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${sdkTool("ninja")}", "-DCMAKE_BUILD_TYPE=Release",
        "-DAIRPLAY_BUILD_JNI=ON", "-DAIRPLAY_BUILD_PROBE=OFF", "-DAIRPLAY_BUILD_TESTS=ON",
    )
}

val buildNativeHost by tasks.registering(Exec::class) {
    dependsOn(configureNativeHost)
    inputs.dir(nativeSrc)
    outputs.dir(hostBuildDir)
    commandLine(sdkTool("cmake"), "--build", hostBuildDir.get().asFile.absolutePath, "--target", "mediaplayer_airplay")
}

val bundleNativeHost by tasks.registering(Copy::class) {
    dependsOn(buildNativeHost)
    from(hostBuildDir) { include("*mediaplayer_airplay.*") }
    into(hostResourcesDir.map { it.dir("native/$hostPlatform") })
}

// Native unit tests (ALAC round trip, HLS master playlist trimming), run with the JVM tests.
val nativeHostTest by tasks.registering(Exec::class) {
    dependsOn(configureNativeHost)
    val dir = hostBuildDir.get().asFile.absolutePath
    commandLine(
        "sh", "-c",
        "'${sdkTool("cmake")}' --build '$dir' --target alac_roundtrip_test hls_master_test && " +
            "'$dir/alac_roundtrip_test' && '$dir/hls_master_test'",
    )
}
tasks.named("jvmTest") { dependsOn(nativeHostTest) }

kotlin.sourceSets.jvmMain {
    resources.srcDir(bundleNativeHost.map { hostResourcesDir.get() })
}
