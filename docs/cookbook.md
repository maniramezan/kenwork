# Cookbook

Task-oriented recipes. All snippets assume `import io.github.maniramezan.kenwork.network.*`
(and `.cache.*` / `.repository.*` where relevant).

For an end-to-end example that combines these recipes, read the test-verified
[`samples` module](../samples/src/main/kotlin/io/github/maniramezan/kenwork/samples).

For shared KMP clients, see [platforms.md](platforms.md). For GraphQL envelopes and partial-data
handling, see [graphql.md](graphql.md). Review [security.md](security.md) before enabling logging,
persisting account data, or retrying mutations.

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

Endpoints that return no meaningful body (e.g. `204 No Content`) can be requested as `Unit`
(what `execute` does) or as `EmptyResponse`; neither runs the JSON decoder:

```kotlin
val done: EmptyResponse = client.request(DeleteVideo(id = 42))
```

To send pre-encoded bytes instead of a JSON value, set `body` on the endpoint and give it a
`Content-Type` header (without one it is sent as `application/octet-stream`). A typed body passed
to `request` takes precedence over `endpoint.body`.

```kotlin
data class UploadCsv(val csv: ByteArray) : NetworkEndpoint {
    override val baseUrl = "https://api.example.com"
    override val path = "v1/imports"
    override val method = HttpMethod.PUT
    override val headers = mapOf("Content-Type" to "text/csv")
    override val body = csv
}

client.execute(UploadCsv(file.readBytes()))
```

The whole body is held in memory; streaming and multipart uploads are not supported yet.

## Timeouts and the OkHttp engine

`timeoutMillis` (default 30 s) bounds each request as a whole. OkHttp's own
connect and read timeouts (10 s each by default) apply underneath it; change them, or anything else
on the builder, with `okHttpConfig`. `engineInterceptors` adds OkHttp interceptors (for example a
traffic recorder in debug builds).

```kotlin
val client = NetworkClient(NetworkClientConfiguration(
    timeoutMillis = 60_000,
    okHttpConfig = {
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
    },
    engineInterceptors = if (BuildConfig.DEBUG) listOf(debugRecorder) else emptyList(),
))
```

`okHttpConfig`, `engineInterceptors`, `okHttpCache`, and `sslPinning` apply only to the built-in
OkHttp engine. They are ignored when you pass `engine`. Client timeouts default to whole-request
`timeoutMillis`, with optional `connectTimeoutMillis` and `socketTimeoutMillis`; individual endpoints
can override the request timeout via `NetworkEndpoint.timeoutMillis`.

## Client lifecycle

Create one `NetworkClient` per credential scope and reuse it. Swapping configuration (e.g. a new
base policy after sign-in) is safe mid-flight; the old engine closes once its requests drain:

```kotlin
client.updateConfiguration(NetworkClientConfiguration(authorizationProvider = signedInProvider))

// When the owning lifecycle ends (e.g. sign-out):
client.close()               // suspending; lets in-flight requests finish
signedInProvider.close()     // cancels any pending token refresh
repository.close()           // cancels loads only when the repository owns its scope
```

The [authenticated-session sample](../samples/src/main/kotlin/io/github/maniramezan/kenwork/samples/AuthenticatedSession.kt)
returns an `AuthenticatedSession` that owns both the client and its refresh provider. Share
`session.client` for requests and call `session.close()` on sign-out to cancel refreshes and
prevent later requests. An explicitly supplied engine remains caller-owned and is closed separately.

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

With `OAuthAuthorizationProvider`, late `401` responses for an already-replaced token replay
with the current token instead of minting another one. Concurrent refreshes still share one
in-flight operation, and `maxAuthRefreshAttempts` continues to bound each request's replays.

## Error handling

```kotlin
try {
    val v: Video = client.request(GetVideo(id))
} catch (e: NetworkError.NotFound) {
    // 404
} catch (e: NetworkError.ServerError) {
    log(e.statusCode) // Raw response bodies can contain credentials or personal data.
} catch (e: NetworkError) {
    // Unauthorized, Timeout, NoInternetConnection, DecodingFailed, ...
    val status: Int? = e.httpStatusCode   // 401/403/404/5xx…, or null for transport failures
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
    maxSizeBytes = 50L * 1024 * 1024, // default; pass null for an unbounded cache
)

// Two-tier: memory over the disk cache, promoting hits with their original timestamp.
val layered = LayeredCache(memory = InMemoryCache<Video>(maxSize = 100), persistent = disk)

// Drop entries older than an hour, e.g. from a periodic job. Observers get CacheChange.Removed.
// Supported on both InMemoryCache and FileSystemCache:
memoryCache.removeExpiredEntries(maxAgeMillis = 60 * 60 * 1000L)
disk.removeExpiredEntries(maxAgeMillis = 24 * 60 * 60 * 1000L)
```

A repository using `CachePolicy.ReturnCacheIfNotExpired` skips expired entries on read and reloads.

### HTTP caching (`Cache-Control` / `ETag`)

The caches above store decoded values for repositories. Separately, OkHttp can cache raw HTTP
responses and revalidate them with the server (`304 Not Modified`) by honoring the server's
`Cache-Control` and `ETag` headers:

```kotlin
val client = NetworkClient(NetworkClientConfiguration(
    okHttpCache = okhttp3.Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024),
))
```

This only helps when the server sends caching headers, and only on the built-in OkHttp engine.
Responses for authenticated requests are stored on disk; see [security.md](security.md).

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

To change the policy for a block of calls without reconfiguring the client, use `withRetryPolicy`.
It's useful when you run your own retry loop and don't want the client retrying underneath it:

```kotlin
// Exactly one attempt for these calls, whatever the client's configured policy.
val user = withRetryPolicy(RetryPolicy.None) { client.request<User>(UserEndpoint(id)) }
```

When your outer retry loop also needs the client's configured connectivity gate, opt into a
bounded wait before each request:

```kotlin
val user = withRetryPolicy(RetryPolicy.None, awaitReachabilityBeforeRequest = true) {
    client.request<User>(UserEndpoint(id))
}
```

`MutationQueue` enables this option automatically for every attempt. It retains the client's
configured gate and `reachabilityWaitMillis` while its own `retryPolicy` controls transient
retries. With the defaults, a mutation has at most 3 queue attempts. OAuth's `401` refresh-and-replay
is a separate path and may send more than one HTTP request within an attempt.

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

// streamOrNull also emits null when the value is removed, expires, or the store is cleared
// (e.g. on sign-out), so the UI can clear stale content.
repo.streamOrNull(GetVideo(42), key).collect { video -> if (video == null) showEmpty() else render(video) }

// Lower level: observe a cache directly.
cache.changes().collect { change -> /* CacheChange.Updated / Removed / Cleared */ }
```

Cache change notifications are invalidations, not a lossless event log. When maintaining a
current value directly from `cache.changes()`, re-read your key on every notification, even when
it names another key: bursts can drop notifications, and layered-cache writes can evict other
keys. Repository streams do this automatically and conflate bursts before reading.

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

// Cancel pending or retrying work for a key, including its persisted record.
queue.cancel(MutationKey.of("like", "video", 42))
```

The queue retains the most recent statuses for up to 64 idle keys. Set `maxStatuses` on
`MutationQueue` if the UI needs a different retention limit; observe a flow while its mutation is
active to receive every status transition.

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
        // The reified factory records LikeBody's TypeInfo, which the client needs to serialize
        // the body on replay. (A typed body without its TypeInfo fails with EncodingFailed.)
        return DecodedMutation(SetLikeState(p.videoId), LikeBody(p.liked))
    }
    @Serializable private data class Payload(val videoId: Int, val liked: Boolean)
}

queue.enqueue(key, SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec)

// At app startup, with the same store + every codec you enqueue with registered upfront:
val queue = MutationQueue(apiClient = client, scope = appScope, store = durableStore, codecs = listOf(SetLikeStateCodec))
queue.restore()   // replays whatever didn't finish before the process died
```

`queue.cancel(key)` removes matching persisted records even before `restore()` has run. Enqueue,
restore, and cancellation serialize queue admission with persistence so a concurrent operation
cannot replay a cancelled snapshot afterwards.

`InMemoryMutationStore` is the default in-process store and does not survive process death. For
durability across app restarts, `:mutations` ships `FileMutationStore`:

```kotlin
val store = FileMutationStore(file = File(context.filesDir, "kenwork/mutations.json"))
val queue = MutationQueue(apiClient = client, scope = appScope, store = store, codecs = listOf(SetLikeStateCodec))
```

`FileMutationStore` caches loaded records in memory, performs atomic file replacements on every write,
and provides a `clear()` method to purge persisted records on sign-out. For databases (e.g. Room or SQLDelight),
implement `MutationStore` directly (three suspend functions over the `@Serializable` `MutationRecord`).

Keep the file in app-private storage, and delete it on sign-out (see below). Records hold
whatever your codec encodes, so never put credentials in a payload.

## Sign-out checklist

Account-scoped state lives in several objects. On sign-out, in this order:

```kotlin
// 1. Stop background work so nothing runs with the old account. MutationQueue runs on your scope.
mutationScope.cancel()
// 2. Fail any pending or later token refresh instead of minting a token for the old account.
oauthProvider.close()
// 3. Stop new requests; in-flight ones finish first.
client.close()
repository.close()                 // only cancels a scope the repository created itself
// 4. Remove account data at rest.
layeredCache.removeAll()           // clears memory and disk; streamOrNull observers get null
fileMutationStore.clear()          // your durable store: drop records queued for the old account
okHttpCache?.evictAll()            // if you enabled HTTP caching
```

Then build a fresh client, provider, queue, and repository for the next account. Reusing cache
directories is fine once they are cleared; namespacing them by account ID avoids the question.
See [security.md](security.md) for the reasoning behind each step.

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

A listener runs synchronously on the request's coroutine, so keep it fast and hand heavy work
off. If it throws, the exception is logged and dropped; the request's result is unaffected.

## Logging

```kotlin
KenworkLogger.level = LogLevel.DEBUG
KenworkLogger.sink = LogSink { level, category, message, t -> Timber.log(/* ... */) }
```

To also log each HTTP request, set the client's `logLevel` to `INFO` (request and response lines)
or `DEBUG` (adds headers and bodies). Those lines are emitted at debug level, so they appear only
when `KenworkLogger.level` is `DEBUG` too. The default client `logLevel` (`WARNING`) installs no
request logger at all. Bodies can contain credentials and personal data; keep this off in release
builds.

## OpenTelemetry

The published kenwork modules have no OpenTelemetry dependency. Apps supply their own API, SDK,
exporter, and `opentelemetry-extension-kotlin` versions and connect them through `RequestInterceptor`,
`RequestHeaderProvider`, `NetworkEventListener`, and `LogSink`/`StructuredLogSink`.

Use the Kotlin extension's `asContextElement()` when invoking suspending work. A raw
`makeCurrent()` scope stores context in a thread-local and does not follow coroutine suspension
or dispatcher changes. The [compiled tracing sample](../samples/src/main/kotlin/io/github/maniramezan/kenwork/samples/TracingInterceptor.kt)
is tested across a dispatcher switch, on failure, and on cancellation. Its spans use the HTTP
method as a bounded name; use stable route names if your app needs more detail.

```kotlin
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

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
            val span = tracer.spanBuilder(endpoint.method.value)
                .setAttribute("http.request.resend_count", attempt.toLong())
                .startSpan()
            return try {
                withContext(span.asContextElement()) { proceed() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                span.recordException(error)
                span.setStatus(StatusCode.ERROR)
                throw error
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

To test code *above* the client (repositories, mutation queues, ViewModels), skip HTTP entirely
with `FakeApiClient`. It records every request and returns whatever its handler produces:

```kotlin
@Test fun cachesVideos() = runTest {
    val api = FakeApiClient { request -> if (request.index == 0) Video(42, "Hi") else throw NetworkError.Timeout }
    val repo = GenericRepository<Video>(api, CacheBasedLocalDataSource(InMemoryCache()), scope = backgroundScope)
    repo.fetch(GetVideo(42), key)
    repo.fetch(GetVideo(42), key)          // served from cache
    assertEquals(1, api.requests.size)
}

// Assert on logging, e.g. that no token is ever logged; the previous sink/level are restored.
withRecordedLogs(LogLevel.DEBUG) { sink ->
    runBlocking { client.request<Video>(GetVideo(42)) }
    assertTrue(sink.entries.none { "Bearer" in it.message })
}
```
