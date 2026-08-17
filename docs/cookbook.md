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

## Mutations (fire-and-forget writes with retry)

The `:mutations` module is for "forgivable" writes — likes, follows, and similar mutations a
ViewModel wants to apply optimistically and fire off in the background, instead of `await`ing them
inline and blocking the UI on a round trip. `MutationQueue` enqueues and returns immediately; the
actual `ApiClient.request` call (and any retries) run on a `CoroutineScope` you own, so the
mutation survives the calling screen going away.

```kotlin
val queue = MutationQueue(apiClient = client, scope = appScope)

// Returns immediately. POST/PATCH aren't retried by NetworkClient's own DefaultRetryPolicy, but
// MutationQueue's default retryPolicy opts non-idempotent methods back in (see below).
queue.enqueue(MutationKey.of("like", "video", 42), SetLikeState(videoId = 42), LikeBody(liked = true))

// Reflect outcome in the UI (rollback logic is yours — this only reports what happened).
queue.statusFlow(MutationKey.of("like", "video", 42)).collect { status ->
    when (status) {
        is MutationStatus.Failed -> rollbackOptimisticLike()
        MutationStatus.Succeeded, is MutationStatus.Retrying, MutationStatus.Pending, null -> Unit
    }
}
```

**Coalescing.** Enqueueing under the same `MutationKey` while a mutation is still pending/retrying
replaces it — including cancelling an in-progress retry backoff — so rapidly toggling like/unlike
collapses to one call for the final desired state instead of replaying every intermediate one.

**Retry.** `MutationQueue` reuses `RetryPolicy`, but as its own setting — distinct from
`NetworkClientConfiguration.retryPolicy` — so opting a mutation into retrying `POST`/`PATCH` never
loosens the underlying `NetworkClient`'s own (conservative) default:

```kotlin
MutationQueue(
    apiClient = client,
    scope = appScope,
    defaultRetryPolicy = DefaultRetryPolicy(retryNonIdempotent = true),   // the default
)

// Or opt a single mutation in/out, overriding the queue default:
queue.enqueue(key, endpoint, body, retryPolicy = DefaultRetryPolicy(retryNonIdempotent = true))
```

**Persistence.** By default mutations are in-memory only (lost on process death). To survive a
relaunch, describe the mutation as data instead of a closure — implement a `MutationCodec` that
turns your endpoint + body into JSON and back, and pass it to `enqueue`:

```kotlin
object SetLikeStateCodec : MutationCodec<LikeBody> {
    override val id = "set-like-state"
    override fun encode(endpoint: NetworkEndpoint, body: LikeBody?) =
        Json.encodeToString(Payload((endpoint as SetLikeState).videoId, body?.liked ?: false))
    override fun decode(payload: String): DecodedMutation<LikeBody> {
        val p = Json.decodeFromString<Payload>(payload)
        return DecodedMutation(SetLikeState(p.videoId), LikeBody(p.liked), null)
    }
    @Serializable private data class Payload(val videoId: Int, val liked: Boolean)
}

queue.enqueue(key, SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec)

// At app startup, with the same store + every codec you enqueue with registered upfront:
val queue = MutationQueue(apiClient = client, scope = appScope, store = durableStore, codecs = listOf(SetLikeStateCodec))
queue.restore()   // replays whatever didn't finish before the process died
```

`InMemoryMutationStore` is the shipped default; implement `MutationStore` (three suspend
functions: `save`/`remove`/`loadAll` over the fully-`@Serializable` `MutationRecord`) against
SQLDelight/Room/DataStore for real durability.

## SSL pinning

```kotlin
val pinning = SslPinningConfiguration.pinning(
    pinnedHosts = mapOf("api.example.com" to setOf(
        // Deploy the current and backup SPKI pins before rotating the certificate key.
        SslPinningConfiguration.Pin.publicKeySha256("CURRENT_PUBLIC_KEY_SHA256_BASE64="),
        SslPinningConfiguration.Pin.publicKeySha256("BACKUP_PUBLIC_KEY_SHA256_BASE64="),
    )),
    includesSubdomains = true,
)
val client = NetworkClient(NetworkClientConfiguration(sslPinning = pinning))
```

Pinning failures block all connections to the host. Validate pins against the production certificate
before release, and keep both the current and next public-key pins deployed during key rotation.

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

## OpenTelemetry

Kenwork has **no dependency on `opentelemetry-api`, any OTel SDK, or any exporter** — and never will,
by design, since apps disagree on OTel version, SDK, and exporter. Instead it exposes generic,
dependency-free hooks (`RequestInterceptor`, `RequestHeaderProvider`, the existing
`NetworkEventListener`, and `LogSink`/`StructuredLogSink`) that carry enough information — timing,
identifiers, attributes, start+end — for *your app* to build real OTel spans, metrics instruments, and
log records. The same hooks work identically for Datadog, Sentry, or a homegrown backend; this recipe
just illustrates the OTel case since it's the most demanding one (it also needs context propagation).

```kotlin
// In *your app's* code — this is where the opentelemetry-api dependency lives, not in Kenwork.
val tracer: Tracer = openTelemetry.getTracer("com.example.app")
val propagator: TextMapPropagator = openTelemetry.propagators.textMapPropagator

// 1. Tracing: one span per attempt (retries become sibling spans; `attempt` is recorded as an
//    attribute so they're still visibly one logical operation). Wrap your own call to
//    `client.request(...)` in a parent span first if you want retries nested under it.
// A plain (not `fun`) interface — `intercept`'s type parameter makes it ineligible for SAM
// conversion, so it's implemented with an object expression rather than a lambda.
val tracingInterceptor =
    object : RequestInterceptor {
        override suspend fun <T> intercept(endpoint: NetworkEndpoint, attempt: Int, proceed: suspend () -> T): T {
            val span = tracer.spanBuilder("${endpoint.method.value} ${endpoint.path}")
                .setAttribute("http.request.resend_count", attempt.toLong())
                .startSpan()
            return try {
                span.makeCurrent().use { proceed() }
            } catch (t: Throwable) {
                span.recordException(t)
                span.setStatus(StatusCode.ERROR)
                throw t
            } finally {
                span.end()
            }
        }
    }

// 2. Context propagation: inject the span `tracingInterceptor` just made current into this same
//    attempt's outgoing request headers (W3C traceparent/tracestate, or baggage).
val headerProvider =
    RequestHeaderProvider { _, _ ->
        val headers = mutableMapOf<String, String>()
        propagator.inject(Context.current(), headers) { carrier, key, value -> carrier?.set(key, value) }
        headers
    }

// 3. Metrics: NetworkEvent already carries endpointId/method/statusCode/durationMs/attempt, plus
//    isFinalAttempt so a duration histogram gets exactly one observation per logical request.
val requestDuration = meter.histogramBuilder("http.client.request.duration").ofLongs().build()
val requestCount = meter.counterBuilder("http.client.request.count").build()
val eventListener =
    NetworkEventListener { event ->
        val attrs = Attributes.of(
            AttributeKey.stringKey("http.route"), event.endpointId,
            AttributeKey.stringKey("http.request.method"), event.method,
        )
        if (event.isFinalAttempt) requestDuration.record(event.durationMs, attrs)
        requestCount.add(1, attrs)
    }

// 4. Logs: bridge KenworkLogger's sink to the OTel Logs API, using StructuredLogSink so the
//    attributes KenworkLogger already carries land as real OTel `Attributes`, not a flat string.
KenworkLogger.level = LogLevel.DEBUG
KenworkLogger.sink = object : StructuredLogSink {
    private val otelLogger = openTelemetry.logsBridge.get("com.example.app")

    override fun log(level: LogLevel, category: LogCategory, message: String, throwable: Throwable?) =
        log(level, category, message, throwable, emptyMap())

    override fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
        attributes: Map<String, Any?>,
    ) {
        val builder = otelLogger.logRecordBuilder()
            .setSeverity(level.toOtelSeverity())
            .setBody(message)
        attributes.forEach { (k, v) -> builder.setAttribute(AttributeKey.stringKey(k), v.toString()) }
        throwable?.let { builder.setAttribute(AttributeKey.stringKey("exception.message"), it.message ?: "") }
        builder.emit()
    }
}

val client = NetworkClient(
    NetworkClientConfiguration(
        requestInterceptor = tracingInterceptor,
        requestHeaderProvider = headerProvider,
        eventListener = eventListener,
    ),
)
```

`RequestInterceptor` is called once per attempt (matching `NetworkEvent.attempt`), not once for the
whole logical request — a span per attempt is the more useful default for HTTP client tracing (each
retry gets its own accurate start/end), and if you also want one span covering the entire retried
request, just wrap your own call to `client.request(...)` in a parent span; retries then nest under it
naturally via `Context.current()`. `RequestHeaderProvider` runs per attempt too, immediately before
that attempt is sent, so it can read back whatever the interceptor just made "current."

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
