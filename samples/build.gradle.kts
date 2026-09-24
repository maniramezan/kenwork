// Runnable, test-verified usage samples. Not published (see the root build) and exempt from
// explicit-API mode and the coverage gate (see build-logic), so it reads like app code.
plugins {
    alias(libs.plugins.kenwork.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.maniramezan.kenwork.samples"
}

dependencies {
    implementation(project(":network"))
    implementation(project(":cache"))
    implementation(project(":repository"))
    implementation(project(":mutations"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
