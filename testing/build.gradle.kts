plugins {
    alias(libs.plugins.kenwork.android.library)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "io.github.maniramezan.kenwork.testing"
}

dependencies {
    // Test utilities are meant to be on a consumer's test classpath, so expose deps as api.
    api(project(":network"))
    api(project(":cache"))
    api(libs.ktor.client.mock)
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)

    testImplementation(libs.kotlin.test.junit)
}
