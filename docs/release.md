# Releasing kenwork

Releases are fully automated with [release-please](https://github.com/googleapis/release-please)
and the [vanniktech maven-publish](https://github.com/vanniktech/gradle-maven-publish-plugin)
plugin. You do not bump versions or upload artifacts by hand.

## The flow

1. Merge PRs to `main` with [Conventional Commit](https://www.conventionalcommits.org) titles:
   - `feat:` → minor bump, `fix:`/`perf:`/`refactor:` → patch, `feat!:` / `BREAKING CHANGE:` →
     major, `chore:`/`docs:`/`test:`/`ci:` → no release.
2. `ci.yml` runs `./gradlew check` (tests, detekt, ktlint, Android lint, JaCoCo gate).
3. After CI passes, `release-please.yml` opens/updates a **release PR** that bumps the version in
   `.release-please-manifest.json` — the single source of truth. The Gradle build reads it from
   there (`build.gradle.kts`), so no other file stores the version.
4. Merging the release PR creates tag `vX.Y.Z` and invokes `release.yml`, which verifies the tag
   matches the manifest version, runs `check`, then:
   ```
   ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
   ```
   vanniktech (with `SONATYPE_HOST=CENTRAL_PORTAL`) creates, uploads, signs, and **releases** the
   deployment to Maven Central, and a GitHub Release is created.
5. `docs.yml` publishes the aggregated Dokka API site to GitHub Pages.

## Required GitHub secrets

| Secret | Purpose |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal token password |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored GPG private key |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | GPG key passphrase |
| `RELEASE_PLEASE_APP_ID` *(optional)* | GitHub App id so the release PR triggers CI and can auto-merge |
| `RELEASE_PLEASE_APP_PRIVATE_KEY` *(optional)* | GitHub App private key |

The `io.github.maniramezan` namespace must be verified on the Central Portal (the same account that
publishes the ComposeUIComponents libraries).

## Local verification

```bash
# Produce all artifacts into ~/.m2 for local consumers.
./gradlew publishToMavenLocal

# Validate the publish path resolves signing/credentials without releasing.
./gradlew publishAllPublicationsToMavenCentralRepository --dry-run
```
