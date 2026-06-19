import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

internal const val COMPILE_SDK = 36
internal const val MIN_SDK = 26

internal fun Project.configureAndroidLibrary(extension: LibraryExtension) {
    extension.apply {
        compileSdk = COMPILE_SDK

        defaultConfig {
            minSdk = MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        // Robolectric-driven unit tests need Android resources on the test classpath;
        // returning default values keeps un-shadowed Android stubs (e.g. android.util.Log)
        // from throwing in plain JVM unit tests.
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
            }
        }
    }
}
