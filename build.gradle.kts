plugins {
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.maven.publish) apply false
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
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
