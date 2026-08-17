plugins {
    alias(libs.plugins.kenwork.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.maniramezan.kenwork.mutations"
}

dependencies {
    api(project(":network"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
