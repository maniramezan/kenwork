import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 26

/**
 * Robolectric's default repo1.maven.org endpoint is fetched at test runtime and is prone to
 * rate-limiting shared CI runners. Use Maven Central's canonical endpoint, matching Gradle's
 * mavenCentral() repository.
 */
internal const val ROBOLECTRIC_REPO_URL = "https://repo.maven.apache.org/maven2"

/**
 * Robolectric 4.17 reflects into JDK internals that the module system closes off starting with
 * JDK 17; without these opens, tests fail with IllegalAccessException from AndroidInterceptors
 * before running. https://robolectric.org/getting-started/
 */
internal val ROBOLECTRIC_JVM_ARGS =
    listOf(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )

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
                all {
                    it.systemProperty("robolectric.dependency.repo.url", ROBOLECTRIC_REPO_URL)
                    it.jvmArgs(ROBOLECTRIC_JVM_ARGS)
                }
            }
        }
    }
}
