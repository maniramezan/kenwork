# Cookbook

Task-oriented recipes. All snippets assume `import io.github.maniramezan.kemwork.network.*`
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

// Two-tier: memory over a disk/DB cache, promoting hits with their original timestamp.
val layered = LayeredCache(memory = InMemoryCache<Video>(maxSize = 100), persistent = diskCache)
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
KemworkLogger.level = LogLevel.DEBUG
KemworkLogger.sink = LogSink { level, category, message, t -> Timber.log(/* ... */) }
```

## Testing (with the `:testing` module)

```kotlin
import io.github.maniramezan.kemwork.testing.*

@Test fun loadsVideo() = runBlocking {
    val client = mockNetworkClient { jsonResponse("""{"id":42,"title":"Hi"}""") }
    val video: Video = client.request(GetVideo(42))
    assertEquals(42, video.id)
}
```
