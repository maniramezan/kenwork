# kenwork

A Kotlin-first networking library for Android and Kotlin Multiplatform — typed endpoints, a coroutine HTTP client with
automatic OAuth refresh, layered caching, and a repository abstraction. **kenwork is the Kotlin
counterpart of [SwiftyNetwork](https://github.com/maniramezan/SwiftyNetwork)**: the two libraries
expose the same concepts with idiomatic APIs on each platform.

Built on **Ktor + OkHttp** and **kotlinx.serialization**, 100% coroutines, dependency-injection
free (plain constructors + a `Configuration` object).

## Modules

| Artifact | What it provides |
|---|---|
| `io.github.maniramezan.kenwork:network-core` | KMP `KenworkHttpClient` policy for Android, JVM/Desktop, and iOS with a consumer-supplied Ktor engine |
| `io.github.maniramezan.kenwork:network` | `NetworkEndpoint`, `NetworkClient`, `AuthorizationProvider`/`OAuthAuthorizationProvider`, `RetryPolicy`/`DefaultRetryPolicy`, `NetworkError`, `NetworkMonitor`/`ReachabilityGate`, `SslPinningConfiguration`, `KenworkLogger`, `NetworkEventListener` |
| `io.github.maniramezan.kenwork:cache` | `Cache`/`TimestampedCache`/`PersistentCache`, `InMemoryCache`, `FileSystemCache`, `LayeredCache`, `CachePolicy`, `CacheKey`, `CacheChange` |
| `io.github.maniramezan.kenwork:repository` | `Repository`/`GenericRepository` (with `fetch` + reactive `stream`), `LocalDataSource`/`CacheBasedLocalDataSource` |
| `io.github.maniramezan.kenwork:mutations` | `MutationQueue`, coalescing, retry, observable status, and a pluggable `MutationStore` (in-memory by default) |
| `io.github.maniramezan.kenwork:testing` | `MockEngine` client builder, fakes (`FakeAuthorizationProvider`, `FakeReachabilityGate`), `RecordingRetryPolicy`, recording listener for consumer tests |

## Install

```kotlin
dependencies {
    val kenworkVersion = "0.5.1"
    implementation("io.github.maniramezan.kenwork:network-core:$kenworkVersion") // KMP
    implementation("io.github.maniramezan.kenwork:network:$kenworkVersion")
    implementation("io.github.maniramezan.kenwork:cache:$kenworkVersion")        // optional
    implementation("io.github.maniramezan.kenwork:repository:$kenworkVersion")   // optional
    implementation("io.github.maniramezan.kenwork:mutations:$kenworkVersion")    // optional
    testImplementation("io.github.maniramezan.kenwork:testing:$kenworkVersion")  // optional
}
```

`network-core` targets Android (minimum SDK 26), JVM/Desktop (JDK 17 bytecode), and iOS device and
Apple Silicon simulator. The established `network`, cache, repository, mutations, and testing
artifacts remain Android libraries published as AARs; JVM/Desktop consumers use `network-core`.

Choose `network-core` for shared KMP code, or `network` for the Android endpoint/auth/retry stack;
neither depends on the other. In a KMP build, put `network-core` in `commonMain.dependencies` and
your Ktor engine dependency in the appropriate platform source set. See the
[module boundaries and engine ownership guide](docs/platforms.md).

## 60-second quickstart

```kotlin
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkClient
import io.github.maniramezan.kenwork.network.NetworkClientConfiguration
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.OAuthAuthorizationProvider
import kotlinx.serialization.Serializable

// 1. Describe an endpoint.
data class GetVideo(val id: Int) : NetworkEndpoint {
    override val baseUrl = "https://api.example.com"
    override val path = "v1/videos/$id"
    override val method = HttpMethod.GET
}

@Serializable
data class Video(val id: Int, val title: String)

// 2. Build a client (optionally with an auth provider).
val client = NetworkClient(
    NetworkClientConfiguration(
        authorizationProvider = OAuthAuthorizationProvider(
            initialAccessToken = currentToken,
            refreshTokenHandler = { refreshAccessToken() },
        ),
    ),
)

// 3. Make type-safe requests from a coroutine.
suspend fun loadVideo(): Video = client.request(GetVideo(id = 42))
```

A `401` automatically triggers a single coalesced token refresh and one retry.

## Features

- Type-safe `NetworkEndpoint` with sensible defaults.
- Coroutine `NetworkClient` with reified `request<T>()` / `request<B, T>(body)`.
- Pluggable `AuthorizationProvider`; `OAuthAuthorizationProvider` does coalesced 401
  refresh-and-retry.
- **Pluggable `RetryPolicy`** — `DefaultRetryPolicy` retries timeouts, lost connectivity, `429`,
  and `5xx` with jittered exponential backoff, honors `Retry-After`, and retries idempotent methods
  only by default. Use `RetryPolicy.None` to disable, or supply your own.
- **Reachability-aware retry** — give the client a `ReachabilityGate` (e.g.
  `networkMonitor.asReachabilityGate()`) and retries park while offline, resuming when the network
  returns instead of burning attempts.
- Closed `NetworkError` set (`Unauthorized`, `Forbidden`, `NotFound`, `ServerError`, `Timeout`,
  `NoInternetConnection`, `DecodingFailed`, …).
- Caching: `InMemoryCache` (LRU + expiry), **`FileSystemCache`** (durable `PersistentCache`),
  `LayeredCache` (memory + persistent with timestamp promotion), `CachePolicy`.
- `GenericRepository` coordinating network + cache with write-through, **single-flight** load
  coalescing, and a **reactive `stream()`** that re-emits on cache changes (offline-first).
- `NetworkMonitor` connectivity (`StateFlow`), OkHttp `SslPinningConfiguration`, `KenworkLogger`,
  and a `NetworkEventListener` telemetry hook (with per-attempt retry events).

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — layering & concurrency model.
- [docs/cookbook.md](docs/cookbook.md) — task-oriented recipes.
- [docs/platforms.md](docs/platforms.md) — platform support, shared code, and client ownership.
- [docs/security.md](docs/security.md) — credentials, logging, persistence, and retry boundaries.
- [docs/graphql.md](docs/graphql.md) — GraphQL transport and application-error handling.
- [docs/parity.md](docs/parity.md) — SwiftyNetwork ↔ kenwork mapping.
- [MIGRATION.md](MIGRATION.md) — moving an existing Ktor/auth layer onto kenwork.
- [docs/release.md](docs/release.md) — how releases reach Maven Central.
- API reference (Dokka): published to GitHub Pages on pushes to `main`.

## License

MIT — see [LICENSE](LICENSE).
