import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

public class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        // AGP 9 ships built-in Kotlin support, so we must NOT apply
        // `org.jetbrains.kotlin.android` ourselves — only the Android library plugin.
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidLibrary(this)
        }

        tasks.withType(KotlinCompile::class.java).configureEach {
            val isTestCompilation = name.contains("UnitTest") || name.contains("AndroidTest")
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                // Public API of a published library must be explicit (visibility + return types),
                // but tests are not published, so don't burden them with explicit-api.
                if (!isTestCompilation) {
                    freeCompilerArgs.add("-Xexplicit-api=strict")
                }
            }
        }
    }
}
