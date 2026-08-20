import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
}

kotlin {
    explicitApi()
    android {
        namespace = "io.github.maniramezan.kenwork.core"
        compileSdk = 36
        minSdk = 26
        compilerOptions.jvmTarget = JvmTarget.JVM_17
        withHostTest {}
    }
    jvm { compilerOptions.jvmTarget = JvmTarget.JVM_17 }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
    }
}
