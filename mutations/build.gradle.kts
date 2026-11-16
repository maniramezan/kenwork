plugins {
    alias(libs.plugins.kenwork.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":network"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.turbine)
        }
    }
}
