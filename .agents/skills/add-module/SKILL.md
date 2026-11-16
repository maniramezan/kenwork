---
name: add-module
description: Scaffold a new Gradle module in kenwork (a new published library artifact, or an internal unpublished module) and register it everywhere the build and docs expect. Use when the user asks to add, create, or split out a module/artifact/library.
---

# Adding a kenwork module

Decide first:

- **Published library** (the default for the stack): uses the `kenwork.kmp.library` convention
  plugin (Android + desktop JVM from one `commonMain`) and is published automatically by the root
  build.
- **Published KMP library that also targets iOS**: follow `network-core/build.gradle.kts` (the
  Android-KMP plugin, and `explicitApi()` set in the module itself).
- **Unpublished** (like `samples`): the Android-only `kenwork.android.library` plugin, excluded from
  publishing, explicit-API mode, and the coverage gate (step 3).

Also decide the dependency direction and check it against `ARCHITECTURE.md`. Never make `network`
or `cache` depend on a higher layer.

## 1. Create the module

`<name>/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kenwork.kmp.library)
    // alias(libs.plugins.kotlin.serialization)   // only if it declares @Serializable types
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":network"))              // `api` only for types exposed in public signatures
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(project(":testing"))   // junit, kotlin-test, coroutines-test come from the plugin
        }
    }
}
```

The plugin derives the Android namespace (`io.github.maniramezan.kenwork.<name>`), SDK levels,
JVM 17 bytecode, explicit-API mode, the JaCoCo gate on `jvmTest`, and Android lint.

Sources go in `<name>/src/commonMain/kotlin/io/github/maniramezan/kenwork/<name>/`. Android and
the JVM are the only targets, so `commonMain` may use JDK APIs; put code that needs the Android SDK
in `src/androidMain/kotlin`. Tests go in `src/jvmTest/kotlin/...`; Robolectric tests for
`androidMain` code go in `src/androidHostTest/kotlin/...` after opting in with
`kotlin { android { withHostTest { } } }` (see `network/build.gradle.kts`). Add new library versions
to `gradle/libs.versions.toml`, never inline.

Then run `./gradlew :<name>:apiDump` and commit both `api/android/<name>.api` and
`api/jvm/<name>.api`.

## 2. Register it

- `settings.gradle.kts`: `include(":<name>")`.
- Root `build.gradle.kts`: add `dokka(project(":<name>"))` for published modules.
- Root `build.gradle.kts`: add `:<name>:apiDump` and `:<name>:apiCheck` to the aggregate tasks.
- Publishing is automatic for any module that applies `com.android.library` or
  `com.android.kotlin.multiplatform.library`; POM metadata comes from `gradle.properties`.

## 3. Unpublished modules only

- Root `build.gradle.kts`: extend the `if (name == "samples") return@subprojects` guard.
- `build-logic/.../AndroidLibraryConventionPlugin.kt`: extend the `testing`/`samples` exemptions for
  JaCoCo and `isPublished` (explicit API).

## 4. Document it

- `README.md`: add a row to the Modules table and a line to the Install snippet.
- `ARCHITECTURE.md`: the layering diagram and dependency bullets.
- `docs/platforms.md`: the platform/responsibility table.
- `docs/parity.md`: map it to SwiftyNetwork, or list it under "Additions beyond parity".
- `AGENTS.md`: the module table.

## 5. Verify

`./gradlew :<name>:check dokkaGenerate`, then the full `./gradlew check`. Published modules must
meet the 70% line-coverage gate from day one.
