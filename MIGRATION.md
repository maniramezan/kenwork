# Migrating an app onto kenwork

This guide moves a hand-rolled Ktor + auth networking layer onto kenwork. It doubles as the script
used to migrate Novalingo's `:core-network`.

## 1. Depend on kenwork

```kotlin
// settings.gradle.kts (dev, before the first Maven Central release)
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }

// module build.gradle.kts
implementation("io.github.maniramezan.kenwork:network:<version>")
implementation("io.github.maniramezan.kenwork:cache:<version>")        // if used
implementation("io.github.maniramezan.kenwork:repository:<version>")   // if used
```

Run `./gradlew publishToMavenLocal` in the kenwork repo to make `<version>` resolvable locally.

## 2. Replace your auth token source with an `AuthorizationProvider`

Make your existing token provider implement `AuthorizationProvider`:

```kotlin
class FirebaseAuthProvider(private val auth: FirebaseAuth) : AuthorizationProvider {
    override suspend fun currentAuthorization(): AuthorizationType =
        idToken(forceRefresh = false)?.let { AuthorizationType.Bearer(it) } ?: AuthorizationType.None
    override suspend fun refreshAuthorizationIfNeeded(): Boolean =
        idToken(forceRefresh = true) != null
}
```

Delete any hand-written auth-header / 401-retry Ktor plugins — `NetworkClient` does this.

## 3. Build `NetworkClient`s from configuration

```kotlin
val apiClient = NetworkClient(
    NetworkClientConfiguration(
        json = appJson,
        authorizationProvider = firebaseAuthProvider,
        okHttpCache = Cache(File(context.cacheDir, "api_http_cache"), 25L * 1024 * 1024),
        engineInterceptors = listOf(apiTraceInterceptor),   // keep your recorder
        eventListener = analyticsNetworkListener,            // your telemetry
    ),
)

// Unauthenticated client for public CDN content: no provider, no auth headers.
val contentClient = NetworkClient(NetworkClientConfiguration(json = appJson))
```

## 4. Express endpoints as `NetworkEndpoint`, keep your DTOs

Route your existing API methods through `NetworkClient`, leaving DTOs and domain mappers untouched:

```kotlin
private data class GetVideoEndpoint(val id: Int) : NetworkEndpoint {
    override val baseUrl = environment.baseUrl
    override val path = "v1/youtube/videos/$id/"
    override val method = HttpMethod.GET
}

suspend fun getVideo(id: Int): Video =
    apiClient.request<VideoDto>(GetVideoEndpoint(id)).toModel()
```

Keep the public signatures of your API facade unchanged so feature code and tests don't move.
Migrate endpoint group by group (e.g. videos → categories → profile → sync → feedback).

## 5. Wire telemetry

Implement `NetworkEventListener` to forward `NetworkEvent`s to your analytics, and pass it in the
configuration. Your analytics module stays where it is.

## 6. Keep DI thin

kenwork is DI-free. Provide its objects from a small Hilt/Koin module:

```kotlin
@Provides @Singleton
fun provideApiClient(provider: AuthorizationProvider, json: Json): NetworkClient =
    NetworkClient(NetworkClientConfiguration(json = json, authorizationProvider = provider))
```

## 7. Delete the duplicated plumbing

Remove the in-repo auth plugins, tracing wiring you replaced, and any bespoke error mapping now
covered by `NetworkError`. Keep app-specific recorders (e.g. APITrace) and pass them via
`engineInterceptors`.

## 8. Verify

```bash
./gradlew :core-network:test
./gradlew :app:assembleProdDebug
```

Smoke test: a cold-start guest request authenticates; forcing a `401` triggers refresh-and-retry;
the request recorder still captures traffic.

## Upgrading kenwork: 0.1.x → 0.2.0

**Breaking change — retry configuration.** The fixed retry knobs on `NetworkClientConfiguration`
(`maxTransientRetries`, `retryNonIdempotent`, `retryBackoffBaseMillis`, `retryBackoffMaxMillis`) are
replaced by a single pluggable `retryPolicy`. Migrate:

```kotlin
// 0.1.x
NetworkClientConfiguration(
    maxTransientRetries = 3,
    retryNonIdempotent = true,
    retryBackoffBaseMillis = 500,
)

// 0.2.0
NetworkClientConfiguration(
    retryPolicy = DefaultRetryPolicy(
        maxRetries = 3,
        retryNonIdempotent = true,
        backoffBaseMillis = 500,
    ),
)
```

`DefaultRetryPolicy` is the default, so most callers need no change. It now also retries `429` and
honors `Retry-After`. Use `RetryPolicy.None` to disable retries, or implement `RetryPolicy` for
custom logic.

**New, opt-in (no migration needed):**

- `reachabilityGate` on the config (e.g. `networkMonitor.asReachabilityGate()`) parks retries while
  offline.
- `FileSystemCache` — a durable `PersistentCache` for `LayeredCache`'s persistent tier.
- `Repository.stream(...)` and `Cache.changes()` for reactive, offline-first reads.
