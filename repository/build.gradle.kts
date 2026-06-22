plugins {
    alias(libs.plugins.kenwork.android.library)
}

android {
    namespace = "io.github.maniramezan.kenwork.repository"
}

dependencies {
    api(project(":network"))
    api(project(":cache"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
