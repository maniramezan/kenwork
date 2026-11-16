plugins {
    alias(libs.plugins.kenwork.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":network"))
            api(project(":cache"))
            api(libs.kotlinx.coroutines.core)
        }
    }
}
