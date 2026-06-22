# Architecture

kenwork mirrors [SwiftyNetwork](https://github.com/maniramezan/SwiftyNetwork)'s design in
idiomatic Kotlin. "Parity" means the two libraries expose the same concepts — not a shared
binary. iOS stays on SwiftyNetwork (Swift/URLSession); kenwork targets Android/JVM on
Ktor + OkHttp.

## Layering

```
Consumer code
      │
      ▼
Repository  (GenericRepository)            ← :repository
      │            coordinates
      ├──────────────┬───────────────┐
      ▼              ▼               ▼
NetworkClient   Cache / LayeredCache LocalDataSource
 (:network)        (:cache)          (:repository)
      │
      ▼
Ktor HttpClient → OkHttp engine (interceptors, disk cache, CertificatePinner)
```

- `:network` has no dependency on `:cache` or `:repository`.
- `:cache` is independent.
- `:repository` depends on both.

## Concurrency model

Everything is `suspend`-based; there are no callbacks. Swift Concurrency constructs map to Kotlin
coroutines as follows:

| SwiftyNetwork (Swift) | kenwork (Kotlin) |
|---|---|
| `actor` (isolated mutable state) | `Mutex` guarding the state (`InMemoryCache`, `OAuthAuthorizationProvider`, `NetworkClient`) |
| `async`/`await` | `suspend` functions |
| `Task<Bool, Never>` (coalesced refresh) | `Deferred<Boolean>` stored under a `Mutex` |
| `AsyncStream<T>` | `StateFlow<T>` (`NetworkMonitor.updates`); `SharedFlow<CacheChange>` (`Cache.changes`) |
| `Task.sleep` | `delay` |
| `Sendable` | immutable data classes / `Mutex`-guarded state |

### Coalesced repository loads (single-flight)

`GenericRepository` deduplicates concurrent cache-miss loads for the same `CacheKey`: the first
caller starts a single `Deferred` on the repository's scope, registered under a `Mutex`; the rest
await it. One network request serves the whole burst — the same pattern as the auth refresh above.
Pass your own `CoroutineScope` to tie those loads to a lifecycle you own (otherwise an internal
`SupervisorJob` is created and `close()` cancels it).

### Cache tiers

`InMemoryCache` (volatile, LRU) and `FileSystemCache` (durable `PersistentCache`, one file per key)
both implement `TimestampedCache`, so `LayeredCache(memory, disk)` reads through memory → disk and
promotes a disk hit into memory **without losing its age**. `FileSystemCache` keeps `:cache` free of
a serialization dependency by taking caller-supplied `encode`/`decode` lambdas (e.g. wrap
`kotlinx.serialization`), runs file I/O on an injectable context, and reads a corrupt file back as
`null` rather than throwing.

### Atomic cache reads

`Cache.entry(key)` / `LocalDataSource.entry(key)` return value **and** timestamp under a single lock
so a freshness check can't observe a value and a timestamp written by two different operations. The
default composes `value`/`timestamp` (non-atomic); `InMemoryCache`, `LayeredCache`, and
`FileSystemCache` override it.

### Reactive data layer

Beyond `NetworkMonitor`, the data layer is observable: `Cache.changes()` /
`LocalDataSource.changes()` emit a `CacheChange` (`Updated`/`Removed`/`Cleared`) on every mutation
(read-time LRU promotion does **not** emit). `Repository.stream(endpoint, key, policy)` emits an
initial `fetch`, then re-emits the stored value whenever the local store reports a change for that
key (`distinctUntilChanged`), giving an offline-first single-source-of-truth stream for UIs.

## Resilience: retry policy

Retry is governed by a pluggable `RetryPolicy` (`config.retryPolicy`): after each failed attempt
`NetworkClient` asks it for the next delay, or `null` to give up. The default `DefaultRetryPolicy`
retries `Timeout`, `NoInternetConnection`, `429`, and `5xx` up to `maxRetries` (default 2) with
exponential backoff + full jitter, and **honors a `Retry-After` header** (delta-seconds or HTTP
date) over the computed backoff when present. Only **idempotent** methods are retried unless
`retryNonIdempotent` is set, so a `POST`/`PATCH` is never transparently replayed by default. Supply
your own `RetryPolicy` for custom logic, or `RetryPolicy.None` to disable. This is distinct from the
`401` refresh-and-retry path above.

Each attempt emits a `NetworkEvent` carrying its 0-based `attempt` index (one per failed attempt,
then a final success/failure event), so telemetry can observe retry behavior.

**Reachability-aware retry.** When a `reachabilityGate` is configured (e.g.
`networkMonitor.asReachabilityGate()`), the client waits up to `reachabilityWaitMillis` for
connectivity *before* each retry's backoff — so retries park while offline instead of burning
attempts, then resume promptly when the network returns. `ReachabilityGate` is a one-method
interface, so `NetworkClient` stays decoupled from any concrete connectivity source (and testable
without one). `NetworkMonitor.awaitReachable()` exposes the same wait standalone.

`updateConfiguration` reference-counts the active `HttpClient`, so a config swap never closes a
client out from under an in-flight request; the superseded client closes once its last request
drains.

### Coalesced auth refresh

`OAuthAuthorizationProvider` is the choke point. When several requests `401` concurrently, the
first acquires the mutex and starts a single refresh `Deferred`; the rest observe the in-flight
`Deferred` and await the same result. `refreshTokenHandler` therefore runs **once** per burst.
This mirrors SwiftyNetwork's in-flight `Task` and Novalingo's Firebase sign-in mutex.

### 401 refresh-and-retry

`NetworkClient` resolves authorization (endpoint-level first, else the provider), sends the
request, and on `401` calls `refreshAuthorizationIfNeeded()`, waits `retryDelayMillis`, and retries
up to `maxAuthRefreshAttempts` (default 1). If refresh fails it throws
`NetworkError.AuthorizationRefreshFailed`.

## Why Ktor + OkHttp (and not KMP)

The OkHttp engine lets kenwork reuse a mature ecosystem directly: `Interceptor`s (e.g. an APITrace
recorder), the 304-aware disk `Cache`, and `CertificatePinner` for pinning. A full Kotlin
Multiplatform build's only extra benefit would be an iOS target — already served by SwiftyNetwork —
at the cost of `expect/actual` for pinning, connectivity, caching, and tracing. The modules are
layered so a future KMP lift remains possible.

## Zero dependency injection

Like SwiftyNetwork (plain initializers + a `Configuration`), kenwork ships **no DI framework
dependency**. Consumers construct objects directly or wire them with their own DI (Hilt/Koin).
`NetworkClient.shared` offers a process-wide default.

## Testing

`MockEngine` (via the `:testing` module) drives `NetworkClient` with no sockets. Robolectric covers
`NetworkMonitor`. The build enforces a JaCoCo line-coverage gate on the published modules.
Unit tests run on a Java 21 toolchain (Robolectric + compileSdk 36 require it).

The `:testing` module ships doubles for consumers: `mockNetworkClient` (now with `retryPolicy` /
`reachabilityGate`), `jsonResponse`, `RecordingNetworkEventListener`, `FakeAuthorizationProvider`,
`FakeReachabilityGate` (a controllable `ReachabilityGate`), and `RecordingRetryPolicy` (records
retry decisions while delegating).
