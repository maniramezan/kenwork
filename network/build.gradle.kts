plugins {
    alias(libs.plugins.kenwork.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        // NetworkMonitor and AndroidLogSink are covered by Robolectric host tests.
        withHostTest {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.okhttp)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.turbine)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlin.test.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}
