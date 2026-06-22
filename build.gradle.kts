plugins {
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Single source of truth for the published version: the release-please manifest
// (`.release-please-manifest.json`). release-please bumps it in the release PR; Gradle reads it
// here so the version lives in exactly one file.
val libraryVersion: String =
    providers
        .fileContents(layout.projectDirectory.file(".release-please-manifest.json"))
        .asText
        .map { json ->
            Regex("\"\\.\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                ?: error("No \".\" version found in .release-please-manifest.json")
        }.get()

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = libraryVersion
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
}

// Aggregate the published modules' KDoc into a single Dokka HTML site (build/dokka/html)
// via `./gradlew dokkaGenerate`.
dependencies {
    dokka(project(":network"))
    dokka(project(":cache"))
    dokka(project(":repository"))
    dokka(project(":testing"))
}

subprojects {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
    }

    // Every published Android library gets API docs (Dokka) and Maven Central
    // publishing (vanniktech, configured from the root gradle.properties POM_* keys).
    pluginManager.withPlugin("com.android.library") {
        pluginManager.apply("org.jetbrains.dokka")
        pluginManager.apply("com.vanniktech.maven.publish")
    }
}
