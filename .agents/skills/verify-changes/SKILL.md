---
name: verify-changes
description: Run and interpret kenwork's pre-push checks (tests, detekt, ktlint, Android lint, coverage, docs) and self-review a diff before committing, pushing, or declaring work done. Also use to diagnose a red CI run.
---

# Verifying a kenwork change

## 1. Run the checks CI runs

```bash
./gradlew check            # unit tests + detekt + ktlint + Android lint + JaCoCo (70% line gate)
./gradlew dokkaGenerate    # KDoc must render; broken [links] show up here
```

Faster loops while iterating:

```bash
./gradlew :<module>:testDebugUnitTest --tests '*SomeTest*'
./gradlew ktlintFormat                       # then re-run ktlintCheck
./gradlew :<module>:detekt
./gradlew :network-core:jvmTest              # KMP core; iOS needs macOS: :network-core:iosSimulatorArm64Test
```

Reports: `<module>/build/reports/{tests,detekt,ktlint,lint-results*,jacoco}`.

## 2. Interpret failures

- **Coverage gate**: add tests for the new branches rather than lowering `MIN_COVERAGE`
  (`build-logic/.../Jacoco.kt`). `:testing` and `:samples` are exempt by design.
- **detekt**: fix the code. Suppress only with a scoped `@Suppress("Rule")` and a comment saying why.
  Rule-set placement matters in `config/detekt/detekt.yml` (for example, `ReturnCount` is under `style`).
- **Flaky-looking coroutine tests**: usually a missing `runCurrent()` or a real dispatcher
  (`Dispatchers.IO`/`Default`) inside `runTest`. Inject a test dispatcher (`ioContext`, `scope`)
  instead of adding sleeps or retries.
- **Robolectric download or JDK errors**: the build needs JDK 21; Robolectric fetches
  `android-all` from Maven Central on the first run.
- **No Android SDK** (e.g. a sandbox): Android modules can't build. Say so plainly rather than
  claiming the checks passed; `:network-core:jvmTest` still runs.

## 3. Review the diff yourself

Check `git diff` for:

- [ ] Public API changed? Follow the `evolve-public-api` skill (hidden shims, no moved facades).
- [ ] Every new public declaration has a visibility modifier, an explicit type, and KDoc.
- [ ] `CancellationException` is rethrown before any broad `catch`.
- [ ] No credentials, tokens, bodies, or full URLs are logged or persisted.
- [ ] Time, randomness, and dispatchers are injectable in new code, and tests use them.
- [ ] Bug fixes come with a test that fails without the fix.
- [ ] Docs updated: `docs/cookbook.md` for user-facing APIs, the PR description for behavior
  changes, `ARCHITECTURE.md` for structural changes, `docs/parity.md` for SwiftyNetwork mapping.
- [ ] The commit message uses the right Conventional Commit type (see `AGENTS.md`).
