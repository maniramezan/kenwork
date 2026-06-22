# Cookbook

Task-oriented recipes. All snippets assume `import io.github.maniramezan.kenwork.network.*`
(and `.cache.*` / `.repository.*` where relevant).

## Define endpoints

```kotlin
data class ListVideos(val limit: Int, val offset: Int) : NetworkEndpoint {
    override val baseUrl = "https://api.example.com"
    override val path = "v1/videos"
    override val method = HttpMethod.GET
    override val queryItems = listOf("limit" to "$limit", "offset" to "$offset")
}

data class CreateVideo(private val auth: String) : NetworkEndpoint {
    override val baseUrl = "https://api.example.com"
    override val path = "v1/videos"
    override val method = HttpMethod.POST
    override val authorization = AuthorizationType.Bearer(auth)  // per-request override
}
```

## Make requests

```kotlin
val client = NetworkClient()

val page: List<Video> = client.request(ListVideos(limit = 20, offset = 0))
val created: Video = client.request(CreateVideo(auth = token), body = NewVideo(title = "Hi"))
client.execute(DeleteVideo(id = 42))   // ignores the response body
```

## Authorization & automatic refresh

```kotlin
val provider = OAuthAuthorizationProvider(
    initialAccessToken = savedToken,
    refreshTokenHandler = { authApi.refresh(savedRefreshToken)?.accessToken },
)

val client = NetworkClient(
    NetworkClientConfiguration(
        authorizationProvider = provider,
        maxAuthRefreshAttempts = 1,   // refresh + retry once on 401
        retryDelayMillis = 1_000,
    ),
)
```

Implement `AuthorizationProvider` yourself to bridge an existing token source (e.g. Firebase):

```kotlin
class FirebaseAuthProvider(private val auth: FirebaseAuth) : AuthorizationProvider {
    override suspend fun currentAuthorization() =
        auth.currentUser?.getIdToken(false)?.await()?.token
            ?.let { AuthorizationType.Bearer(it) } ?: AuthorizationType.None
    override suspend fun refreshAuthorizationIfNeeded() =
        auth.currentUser?.getIdToken(true)?.await()?.token != null
}
```

## Error handling

```kotlin
try {
    val v: Video = client.request(GetVideo(id))
} catch (e: NetworkError.NotFound) {
    // 404
} catch (e: NetworkError.ServerError) {
    log(e.statusCode, e.body)
} catch (e: NetworkError) {
    // Unauthorized, Timeout, NoInternetConnection, DecodingFailed, ...
}
```

## Caching

```kotlin
val cache = InMemoryCache<Video>(maxSize = 200)
cache.setValue(video, CacheKey.endpoint("videos", mapOf("id" to "42")))

// Durable tier: one file per key. Bring your own value codec (here, kotlinx.serialization),
// so :cache needs no serialization dependency of its own.
val disk = FileSystemCache(
    directory = File(context.cacheDir, "videos"),
    encode = { Json.encodeToString(Video.serializer(), it) },
    decode = { Json.decodeFromString(Video.serializer(), it) },
)

// Two-tier: memory over the disk cache, promoting hits with their original timestamp.
val layered = LayeredCache(memory = InMemoryCache<Video>(maxSize = 100), persistent = disk)
```

## Retry policy

```kotlin
// Default: retries Timeout / NoInternetConnection / 429 / 5xx with jittered exponential backoff,
// honors Retry-After, and retries idempotent methods only.
val client = NetworkClient(NetworkClientConfiguration(
    retryPolicy = DefaultRetryPolicy(maxRetries = 3, retryNonIdempotent = false),
))

// Disable retries:
NetworkClientConfiguration(retryPolicy = RetryPolicy.None)

// Fully custom: return the next delay in millis, or null to stop.
NetworkClientConfiguration(
    retryPolicy = RetryPolicy { attempt, method, error ->
        if (error is NetworkError.ServerError && error.statusCode == 503 && attempt <= 5) 2_000L else null
    },
)
```

Each attempt emits a `NetworkEvent` (0-based `event.attempt`) to the `eventListener`, so retries are
observable in telemetry.

## Reachability-aware retry

```kotlin
val monitor = NetworkMonitor(context).apply { start() }
val client = NetworkClient(NetworkClientConfiguration(
    reachabilityGate = monitor.asReachabilityGate(),
    reachabilityWaitMillis = 15_000,   // cap per retry
))
// On a retry, the client waits (bounded) for connectivity before backing off, so retries don't
// burn attempts while offline. `monitor.awaitReachable()` is also usable standalone.
```

## Repositories

```kotlin
val repo = GenericRepository<Video>(
    networkDataSource = client,
    localDataSource = CacheBasedLocalDataSource(InMemoryCache(maxSize = 100)),
)

val key = CacheKey.endpoint("videos", mapOf("id" to "42"))
val fresh = repo.fetch(GetVideo(42), key, CachePolicy.ReturnCacheIfNotExpired(maxAgeMillis = 60_000))
```

Concurrent `fetch`es that miss the cache for the same key are **coalesced** into one network call.

## Reactive streams (offline-first)

```kotlin
// Emits the initial fetch, then re-emits whenever the cached value for this key changes
// (e.g. a background refresh or a write from another screen). Always converges to the latest value.
repo.stream(GetVideo(42), key, CachePolicy.ReturnCacheElseLoad)
    .collect { video -> render(video) }

// Lower level: observe a cache directly.
cache.changes().collect { change -> /* CacheChange.Updated / Removed / Cleared */ }
```

## SSL pinning

```kotlin
val pinning = SslPinningConfiguration.pinning(
    pinnedHosts = mapOf("api.example.com" to setOf(
        SslPinningConfiguration.Pin.publicKeySha256("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
    )),
    includesSubdomains = true,
)
val client = NetworkClient(NetworkClientConfiguration(sslPinning = pinning))
```

## Connectivity

```kotlin
val monitor = NetworkMonitor(context)
monitor.start()
lifecycleScope.launch { monitor.updates.collect { render(it) } }
// monitor.stop() when done
```

## Telemetry

```kotlin
val client = NetworkClient(NetworkClientConfiguration(
    eventListener = NetworkEventListener { event ->
        analytics.track(event.endpointId, event.statusCode, event.durationMs, event.errorType)
    },
))
```

## Logging

```kotlin
KenworkLogger.level = LogLevel.DEBUG
KenworkLogger.sink = LogSink { level, category, message, t -> Timber.log(/* ... */) }
```

## Testing (with the `:testing` module)

```kotlin
import io.github.maniramezan.kenwork.testing.*

@Test fun loadsVideo() = runBlocking {
    val client = mockNetworkClient { jsonResponse("""{"id":42,"title":"Hi"}""") }
    val video: Video = client.request(GetVideo(42))
    assertEquals(42, video.id)
}

// Exercise retry + reachability with controllable doubles:
@Test fun retriesThenSucceeds() = runBlocking {
    val policy = RecordingRetryPolicy(DefaultRetryPolicy(maxRetries = 1, backoffBaseMillis = 0))
    val gate = FakeReachabilityGate(reachable = true)
    var calls = 0
    val client = mockNetworkClient(retryPolicy = policy, reachabilityGate = gate) {
        calls++
        if (calls == 1) jsonResponse("{}", HttpStatusCode.InternalServerError) else jsonResponse("""{"id":42,"title":"Hi"}""")
    }
    client.request<Video>(GetVideo(42))
    assertEquals(1, policy.decisions.size)   // one retry decision recorded
}
```

`mockNetworkClient` defaults `retryPolicy` to `RetryPolicy.None` so tests are deterministic; opt in
with a `DefaultRetryPolicy`/custom policy as above. `FakeReachabilityGate.setReachable(...)` lets a
test resume a pending `awaitReachable()`.
