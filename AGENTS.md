# AGENTS.md

Guide for AI coding agents and human contributors working in this repository. Read it before
changing code. `README.md` covers library usage; this file covers how to *work on* the library.

## What this repo is

kenwork is a published Kotlin networking library, the Kotlin counterpart of
[SwiftyNetwork](https://github.com/maniramezan/SwiftyNetwork). Apps depend on it from Maven
Central, so **every public signature is a compatibility promise** (see "Public API rules").

| Module | Kind | Depends on | Notes |
|---|---|---|---|
| `network-core` | KMP (Android, JVM, iOS) | Ktor core | Tiny shared client policy; independent of the stack below |
| `network` | Android library | Ktor + OkHttp | Endpoints, `NetworkClient`, auth, retry, errors, logging, telemetry |
| `cache` | Android library | coroutines | `InMemoryCache`, `FileSystemCache`, `LayeredCache`, `CachePolicy` |
| `repository` | Android library | `network`, `cache` | `GenericRepository` (single-flight loads, reactive streams) |
| `mutations` | Android library | `network` | `MutationQueue` (coalescing, retry, persistence) |
| `testing` | Android library | `network`, `cache` | Published test doubles for consumers |
| `samples` | Android library, **not published** | all of the above | Test-verified usage examples |

Dependency direction is strict: `network` never depends on `cache`/`repository`; `cache` depends on
nothing in this repo. Do not add edges without updating `ARCHITECTURE.md`.

Key docs: `ARCHITECTURE.md` (design and concurrency), `docs/cookbook.md` (recipes),
`docs/security.md` (credential/logging/persistence rules), `docs/platforms.md` (KMP boundary),
`docs/parity.md` (SwiftyNetwork mapping), `MIGRATION.md` (upgrade notes), `docs/release.md`.

Repository skills for all AI agents live in `.agents/skills/`. Read the matching `SKILL.md` when
working on a public API (`evolve-public-api`), adding a module (`add-module`), or verifying changes
(`verify-changes`). Edit skills there; `.claude/skills/` contains links to those same files for
Claude Code discovery.

## Build and verify

Requirements: JDK 21 (the Gradle daemon is pinned via `gradle/gradle-daemon-jvm.properties`) and an
Android SDK with compileSdk 37 (set `ANDROID_HOME` or `local.properties`).

```bash
./gradlew check                     # unit tests, detekt, ktlint, Android lint, JaCoCo gate (70% lines)
./gradlew :cache:testDebugUnitTest  # one module's tests
./gradlew :network:testDebugUnitTest --tests '*RetryPolicyTest*'
./gradlew ktlintFormat              # auto-fix formatting
./gradlew dokkaGenerate             # API docs → build/dokka/html
./gradlew :network-core:jvmTest     # KMP core on the JVM (iOS tests need macOS: iosSimulatorArm64Test)
```

CI (`.github/workflows/ci.yml`) runs `./gradlew check` and `dokkaGenerate`. Run `check` before
pushing; use the `verify-changes` skill for the full checklist.

## Code conventions

- **Explicit API mode** (`-Xexplicit-api=strict`) is on for published modules: every public
  declaration needs a visibility modifier and an explicit return type. Default to `internal`.
- **KDoc every public declaration.** When a type mirrors SwiftyNetwork, say so ("Mirrors
  SwiftyNetwork's `X`") and keep `docs/parity.md` in sync.
- **Coroutines only.** `suspend` functions, `Flow`/`StateFlow`, `Mutex` for shared mutable state
  (the analog of a Swift actor). No callbacks, no `runBlocking` in library code, no DI framework.
- **Cancellation:** in `catch (Throwable)` blocks, catch and rethrow `CancellationException` first.
  Never let an internal scope's cancellation leak into a caller that was not cancelled.
- **Errors:** everything a request can fail with maps onto the sealed `NetworkError`. Classify
  "transient" failures only through `NetworkError.isTransient()` (`HttpStatus.kt`) so retry and
  telemetry can't disagree.
- **Time and randomness are injectable** (`currentTimeMillis: () -> Long`, `random: Random`,
  `ioContext`) so tests stay deterministic. Follow that pattern for new time-dependent code.
- **Keep classes focused.** Orchestrators delegate to internal, separately tested helpers (see the
  `:network` file map in `ARCHITECTURE.md`). Prefer a new internal file over growing a 400-line class.
- Formatting is ktlint's (4-space indent, trailing commas, 140-column limit); detekt config lives in
  `config/detekt/detekt.yml`. Fix findings rather than suppressing them; when a suppression is
  justified, scope it to the smallest element and comment why.

## Public API rules (binary compatibility)

Consumers upgrade the AAR without recompiling their dependents, so a change that is
source-compatible can still be **binary-incompatible**. Before touching anything public, use the
`evolve-public-api` skill. In short:

- Never remove, rename, or change the signature of a public/protected declaration. Adding a
  parameter (even with a default) changes the JVM descriptor: keep the old signature as a
  `@Deprecated(level = DeprecationLevel.HIDDEN)` overload that forwards to the new one (see
  `NetworkClientConfiguration`, `NetworkEvent`, `mockNetworkClient`, and `KenworkLogger`).
- Don't move public top-level functions/properties between files (the JVM facade class, e.g.
  `ApiClientKt`, is part of the ABI). Classes and `internal`/`private` declarations can move.
- Don't add subclasses to a public `sealed` type or constants to a public `enum` consumers may
  exhaustively `when` over (`NetworkError`, `CachePolicy`, `CacheChange`, `MutationStatus`,
  `LogLevel`, `LogCategory`) without a major-version plan.
- Don't turn a `data class` into a regular class or vice versa (it changes `componentN`/`copy`).
- Behavior changes that callers could observe belong in `MIGRATION.md`.

## Tests

- Framework: `kotlin.test` + JUnit4, `kotlinx-coroutines-test` (`runTest`, `backgroundScope`,
  `runCurrent`, `advanceUntilIdle`). Name tests with backticked sentences describing behavior.
- HTTP: drive `NetworkClient` with Ktor `MockEngine` (`testClient {}` in `:network` tests,
  `mockNetworkClient {}` elsewhere). Above the client, use `FakeApiClient` from `:testing`.
- Fix a bug by writing the failing test first, then confirm it fails on the old code.
- `:mutations` tests: settle background work with the `settle()` helper, not a bare
  `advanceUntilIdle()` (see its KDoc for why).
- `NetworkMonitor` is covered with Robolectric; other tests are plain JVM
  (`isReturnDefaultValues = true` stubs `android.util.Log`).

## Adding things

- **New module:** use the `add-module` skill (settings, convention plugin, Dokka, publishing,
  README/ARCHITECTURE/platforms tables).
- **New public API:** KDoc, tests, a cookbook recipe when it's user-facing, and the parity table
  when it has (or intentionally lacks) a SwiftyNetwork counterpart. Consider a `:samples` update.
- **New test double for consumers:** put it in `:testing` (published), not in a module's test sources.

## Commits, PRs, and releases

- Conventional Commits drive release-please: `feat:` → minor, `fix:`/`perf:`/`refactor:` → patch,
  `feat!:`/`BREAKING CHANGE:` → major; `docs:`/`test:`/`chore:`/`ci:` don't release.
- Never edit the version by hand: `.release-please-manifest.json` is the single source of truth, and
  release-please also updates the README install snippet (`x-release-please-version`).
- Don't commit `local.properties`, `.claude/settings.local.json`, or build outputs.

## Security

Follow `docs/security.md`. In particular: never log or persist credentials, raw response bodies,
or full URLs by default; keep `AuthorizationType` string forms redacted; treat `okHttpConfig`,
interceptors, and log sinks as trusted code.
