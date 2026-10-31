plugins {
    alias(libs.plugins.mediaplayer.kmp.library)
}

// Domain layer: entities, repository contracts, use cases. No framework / platform dependencies.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
        }
    }
}

android {
    namespace = "app.devper.appplayer.domain"
}
