plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "kenwork.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("kmpLibrary") {
            id = "kenwork.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "kenwork.kotlin.library"
            implementationClass = "KotlinLibraryConventionPlugin"
        }
    }
}
