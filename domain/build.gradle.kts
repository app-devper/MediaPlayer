plugins {
    alias(libs.plugins.mediaplayer.kmp.library)
}

// Domain layer: entities, repository contracts, use cases. No framework / platform dependencies.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "com.appdevper.mediaplayer.domain"
}
