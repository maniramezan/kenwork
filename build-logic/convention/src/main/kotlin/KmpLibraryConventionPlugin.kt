import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A published library compiled for Android and the desktop JVM from one `commonMain` source set.
 *
 * Both targets are JVM-based, so `commonMain` may use JDK APIs directly; only code that needs the
 * Android SDK goes in `androidMain`. Plain tests live in `jvmTest`. A module that needs Android
 * host tests (Robolectric) opts in with `kotlin { android { withHostTest { } } }`.
 */
public class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        // The Android-KMP plugin registers no lint task for main code (KMT-1196); the standalone
        // lint plugin restores `lint`, which `check` runs, as it did for these modules before KMP.
        pluginManager.apply("com.android.lint")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<KotlinMultiplatformExtension> {
            explicitApi()
            jvm {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach {
                namespace = "io.github.maniramezan.kenwork.${project.name.replace('-', '.')}"
                compileSdk = COMPILE_SDK
                minSdk = MIN_SDK
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            sourceSets.getByName("jvmTest").dependencies {
                implementation(libs.findLibrary("junit").get())
                implementation(libs.findLibrary("kotlin-test-junit").get())
                implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            }
        }

        // Android host tests run under Robolectric; see AndroidConfig.kt for why these are needed.
        tasks.withType<Test>().configureEach {
            if (name.contains("AndroidHostTest")) {
                systemProperty("robolectric.dependency.repo.url", ROBOLECTRIC_REPO_URL)
                jvmArgs(ROBOLECTRIC_JVM_ARGS)
            }
        }

        // :testing ships test utilities only, so it is exempt from the coverage gate.
        if (name != "testing") {
            configureKmpJacoco()
        }
    }
}
