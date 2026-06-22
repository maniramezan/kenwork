plugins {
    alias(libs.plugins.kenwork.android.library)
}

android {
    namespace = "io.github.maniramezan.kenwork.cache"
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
