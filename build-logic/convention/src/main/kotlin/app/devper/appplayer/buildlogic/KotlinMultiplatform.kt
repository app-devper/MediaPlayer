package app.devper.appplayer.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { GradleException("Missing version '$alias' in gradle/libs.versions.toml") }.requiredVersion

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { GradleException("Missing library '$alias' in gradle/libs.versions.toml") }

/** Targets every module builds for: Android, Desktop (JVM), iOS device + Apple-silicon simulator. */
internal fun Project.configureKotlinMultiplatform() {
    extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(17)
        // androidTarget() is created by com.android.kotlin.multiplatform.library
        jvm()
        iosArm64()
        iosSimulatorArm64()

        // Only modules that have tests get the test libraries.
        if (file("src").listFiles().orEmpty().any { it.isDirectory && it.name.endsWith("Test") }) {
            sourceSets.commonTest.dependencies {
                implementation(libs.library("kotlin-test"))
                implementation(libs.library("kotlinx-coroutines-test"))
            }
        }
    }
}

/** The Android target of the AGP KMP library plugin: SDK levels from the catalog, Java 17 bytecode. */
internal fun Project.configureAndroidLibrary() {
    extensions.configure<KotlinMultiplatformExtension> {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            compileSdk = libs.version("android-compileSdk").toInt()
            minSdk = libs.version("android-minSdk").toInt()
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
    }
}
