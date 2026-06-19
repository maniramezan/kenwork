# kemwork

A Kotlin-first networking library for Android — typed endpoints, a coroutine HTTP client with
automatic OAuth refresh, layered caching, and a repository abstraction. **kemwork is the Kotlin
counterpart of [SwiftyNetwork](https://github.com/maniramezan/SwiftyNetwork)**: the two libraries
expose the same concepts with idiomatic APIs on each platform.

Built on **Ktor + OkHttp** and **kotlinx.serialization**, 100% coroutines, dependency-injection
free (plain constructors + a `Configuration` object).

## Modules

| Artifact | What it provides |
|---|---|
| `io.github.maniramezan.kemwork:network` | `NetworkEndpoint`, `NetworkClient`, `AuthorizationProvider`/`OAuthAuthorizationProvider`, `NetworkError`, `NetworkMonitor`, `SslPinningConfiguration`, `KemworkLogger`, `NetworkEventListener` |
| `io.github.maniramezan.kemwork:cache` | `Cache`/`TimestampedCache`/`PersistentCache`, `InMemoryCache`, `LayeredCache`, `CachePolicy`, `CacheKey` |
| `io.github.maniramezan.kemwork:repository` | `Repository`/`GenericRepository`, `LocalDataSource`/`CacheBasedLocalDataSource` |
| `io.github.maniramezan.kemwork:testing` | `MockEngine` client builder, fakes, recording listener for consumer tests |

## Install

```kotlin
dependencies {
    implementation("io.github.maniramezan.kemwork:network:0.1.0")
    implementation("io.github.maniramezan.kemwork:cache:0.1.0")        // optional
    implementation("io.github.maniramezan.kemwork:repository:0.1.0")   // optional
    testImplementation("io.github.maniramezan.kemwork:testing:0.1.0")  // optional
}
```

Minimum SDK 26, JDK 17 bytecode.

## 60-second quickstart

```kotlin
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

// 3. Make type-safe requests.
val video: Video = client.request(GetVideo(id = 42))
```

A `401` automatically triggers a single coalesced token refresh and one retry.

## Features

- Type-safe `NetworkEndpoint` with sensible defaults.
- Coroutine `NetworkClient` with reified `request<T>()` / `request<B, T>(body)`.
- Pluggable `AuthorizationProvider`; `OAuthAuthorizationProvider` does coalesced 401
  refresh-and-retry.
- Closed `NetworkError` set (`Unauthorized`, `Forbidden`, `NotFound`, `ServerError`, `Timeout`,
  `NoInternetConnection`, `DecodingFailed`, …).
- Caching: `InMemoryCache` (LRU + expiry), `LayeredCache` (memory + persistent with timestamp
  promotion), `CachePolicy`.
- `GenericRepository` coordinating network + cache with write-through.
- `NetworkMonitor` connectivity (`Flow`), OkHttp `SslPinningConfiguration`, `KemworkLogger`,
  and a `NetworkEventListener` telemetry hook.

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — layering & concurrency model.
- [docs/cookbook.md](docs/cookbook.md) — task-oriented recipes.
- [docs/parity.md](docs/parity.md) — SwiftyNetwork ↔ kemwork mapping.
- [MIGRATION.md](MIGRATION.md) — moving an existing Ktor/auth layer onto kemwork.
- [docs/release.md](docs/release.md) — how releases reach Maven Central.
- API reference (Dokka): published to GitHub Pages on each release.

## License

MIT — see [LICENSE](LICENSE).
