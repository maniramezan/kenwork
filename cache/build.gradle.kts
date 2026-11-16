plugins {
    alias(libs.plugins.kenwork.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
    }
}
