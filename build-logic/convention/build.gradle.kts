plugins {
    `kotlin-dsl`
}

// The plugins themselves are put on the classpath by the root build (`apply false`).
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "mediaplayer.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpApplication") {
            id = "mediaplayer.kmp.application"
            implementationClass = "KmpApplicationConventionPlugin"
        }
        register("kmpCompose") {
            id = "mediaplayer.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
    }
}
