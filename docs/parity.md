# SwiftyNetwork ↔ kenwork parity

kenwork is the Kotlin counterpart of [SwiftyNetwork](https://github.com/maniramezan/SwiftyNetwork).
This table maps each public type/behavior between the two libraries. It also serves as a checklist
for the parity tests in `:network`, `:cache`, and `:repository`.

| Concept | SwiftyNetwork (Swift) | kenwork (Kotlin) |
|---|---|---|
| HTTP methods | `HTTPMethod` | `HttpMethod` |
| Endpoint | `NetworkEndpoint` protocol | `NetworkEndpoint` interface |
| Authorization kinds | `AuthorizationType` | `AuthorizationType` (sealed) |
| Auth provider | `AuthorizationProvider` | `AuthorizationProvider` |
| OAuth provider | `OAuthAuthorizationProvider` (actor) | `OAuthAuthorizationProvider` (`Mutex` + `Deferred`) |
| Client | `NetworkClient` (actor) | `NetworkClient` (`Mutex`-guarded) |
| Client config | `NetworkClientConfiguration` | `NetworkClientConfiguration` |
| Shared instance | `NetworkClient.shared` | `NetworkClient.shared` |
| Error model | `NetworkError` | `NetworkError` |
| Empty body | `EmptyResponse` | `EmptyResponse` |
| Remote source marker | `NetworkDataSource` | `NetworkDataSource` |
| Cache protocol | `Cache` / `TimestampedCache` / `PersistentCache` | same names |
| In-memory cache | `InMemoryCache` (LRU) | `InMemoryCache` (LRU) |
| Layered cache | `LayeredCache` | `LayeredCache` |
| Type erasure helper | `AnyCache` | `AnyCache` (convenience; not required in Kotlin) |
| Cache policy | `CachePolicy` | `CachePolicy` |
| Cache key | `CacheKey` | `CacheKey` |
| Local source | `LocalDataSource` / `CacheBasedLocalDataSource` | same names |
| Repository | `Repository` / `GenericRepository` | same names |
| Connectivity | `NetworkMonitor` (`NWPathMonitor`, `AsyncStream`) | `NetworkMonitor` (`ConnectivityManager`, `StateFlow`) |
| Reachability | `NetworkReachability` | `NetworkReachability` |
| TLS pinning | `SSLPinningConfiguration` | `SslPinningConfiguration` (OkHttp `CertificatePinner`) |
| Logging | `Logger` / `LogLevel` | `KenworkLogger` / `LogLevel` / `LogSink` |

## Intentional differences

- **Telemetry:** kenwork adds `NetworkEventListener`/`NetworkEvent` (ported from Novalingo's
  network telemetry) so analytics can be wired without a DI framework. `NetworkEvent.attempt` gives
  per-attempt visibility into retries.
- **Timestamps:** kenwork uses epoch-millisecond `Long` where SwiftyNetwork uses `Date`.
- **Reified requests:** kenwork exposes `request<T>()` / `request<B, T>(body)` extensions instead of
  passing a `responseType:` argument.
- **`AnyCache`:** kept for symmetry, but Kotlin generics don't require Swift-style type erasure.

## Additions beyond parity

These leverage Kotlin/coroutines idioms and have no required SwiftyNetwork counterpart:

- **Pluggable retry:** `RetryPolicy` / `DefaultRetryPolicy` (timeouts, lost connectivity, `429`,
  `5xx`; jittered exponential backoff; honors `Retry-After`; idempotent-only by default).
- **Reachability-aware retry:** `ReachabilityGate` + `NetworkMonitor.asReachabilityGate()` /
  `awaitReachable()` — retries park while offline and resume on reconnect.
- **Concrete persistent cache:** `FileSystemCache` (file-per-key `PersistentCache`; caller-supplied
  value codec so `:cache` carries no serialization dependency).
- **Reactive data layer:** `Cache.changes()` / `LocalDataSource.changes()` (`Flow<CacheChange>`)
  and `Repository.stream(...)` (`Flow<E>`) for offline-first, single-source-of-truth UIs.
- **Single-flight repository loads:** concurrent cache-miss `fetch`es for one key coalesce into a
  single network call.
