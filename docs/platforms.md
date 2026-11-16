# Platform support and ownership

| Module | Published platform surface | Responsibilities |
|---|---|---|
| `network-core` | Android, JVM/Desktop, iOS arm64 device and simulator | Ktor JSON and redirect policy |
| `network` | Android (AAR), JVM/Desktop (JAR) | Typed endpoints, OAuth, retries, errors, OkHttp integration; connectivity (`NetworkMonitor`) on Android only |
| `cache` | Android (AAR), JVM/Desktop (JAR) | Memory and filesystem storage, layered caching |
| `repository` | Android (AAR), JVM/Desktop (JAR) | Network/cache coordination and reactive reads |
| `mutations` | Android (AAR), JVM/Desktop (JAR) | Coalesced background writes and pluggable persistence |
| `testing` | Android (AAR), JVM/Desktop (JAR) | Test doubles for the endpoint client |

## Android and the desktop JVM

`network`, `cache`, `repository`, `mutations`, and `testing` are published for both Android and
the desktop JVM (JDK 17 bytecode) from one shared source set. Depend on the plain coordinates
(for example `io.github.maniramezan.kenwork:network`); Gradle picks `network-android` or
`network-jvm` from the published module metadata. Build tools that ignore Gradle module metadata
must name the platform artifact directly.

The two platforms differ only where the Android SDK is involved:

- `NetworkMonitor` and `AndroidLogSink` exist on Android only. On the desktop JVM, implement
  `ReachabilityGate` over your own connectivity signal if you want reachability-aware retries.
- `KenworkLogger` defaults to Logcat on Android and to `java.util.logging` (loggers named
  `kenwork.<category>`) on the desktop JVM.

These modules do not target iOS. Use `network-core` there; it does not export the Android stack to
iOS or provide a Swift package/framework. Sharing DTOs and domain logic in a consuming KMP module
does not require moving Android connectivity or disk storage into common code.

## KMP client construction

Add `network-core` to `commonMain.dependencies`. Supply an engine dependency in each platform
source set (for example, Ktor OkHttp on Android and Darwin on iOS), aligned with the Ktor version
used by the library. See [Ktor's engine guide](https://ktor.io/docs/client-engines.html).

```kotlin
import io.github.maniramezan.kenwork.core.KenworkHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.HttpClientEngineConfig

fun <T : HttpClientEngineConfig> makeClient(factory: HttpClientEngineFactory<T>): HttpClient =
    KenworkHttpClient.create(factory)
```

The default JSON policy ignores unknown fields. Passing your own `Json` replaces that policy.
Redirects are disabled by default; `followRedirects = true` opts into Ktor's redirect handling.
`network-core` does not install OAuth refresh, retry, timeout, logging, or domain-error mapping.
The Android `NetworkClient` is a separate implementation with its own defaults, including lenient
JSON and Ktor's default redirect handling.

## Resource ownership

Reuse a client within the lifecycle that owns it. Close clients when that lifecycle ends.
Ktor's engine-factory constructor manages the created engine; an explicitly supplied engine is
caller-owned and must be closed separately after all clients using it have finished. The same
explicit-engine ownership applies to `NetworkClientConfiguration.engine`.

`NetworkClient.close()` is suspending and prevents new requests; already acquired requests drain
before its HTTP client closes. Close `OAuthAuthorizationProvider` separately: it owns its refresh
scope and may be shared by clients. `GenericRepository.close()` cancels only its internally
created scope; network loads in flight or requested afterwards fail with `IllegalStateException`. `MutationQueue` always uses a caller-owned scope. Cancelling a scope does not roll
back an HTTP request already accepted by the server.

## Extending the shared surface

Move platform-independent policy into `network-core` only when a consumer needs the same behavior
on multiple targets. Keep engine integration at platform boundaries and test shared contracts
with `MockEngine`; also test platform-specific TLS/transport behavior with the actual engine.
Preserve public constructor descriptors and default-argument bridges when evolving the Android
API: this repo already carries compatibility overloads for previously published signatures.

Run `./gradlew check dokkaGenerate` for repository checks and API docs. On an Apple Silicon Mac
with Xcode and an iOS simulator installed, run `./gradlew :network-core:iosSimulatorArm64Test` for
native tests. Performance work should start with measured workloads (cache contention, payload
size, retry volume) rather than broad API rewrites.
