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

        buildTypes {
            getByName("debug") {
                enableUnitTestCoverage = true
            }
        }

        // Robolectric-driven unit tests need Android resources on the test classpath;
        // returning default values keeps un-shadowed Android stubs (e.g. android.util.Log)
        // from throwing in plain JVM unit tests.
        testOptions {
            unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = true
                // Robolectric's default repo1.maven.org endpoint is independently fetched at
                // test runtime and is prone to rate-limiting shared CI runners. Use Maven
                // Central's canonical endpoint, matching Gradle's mavenCentral() repository.
                all {
                    it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
                }
            }
        }
    }
}
