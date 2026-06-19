# Architecture

kemwork mirrors [SwiftyNetwork](https://github.com/maniramezan/SwiftyNetwork)'s design in
idiomatic Kotlin. "Parity" means the two libraries expose the same concepts — not a shared
binary. iOS stays on SwiftyNetwork (Swift/URLSession); kemwork targets Android/JVM on
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

| SwiftyNetwork (Swift) | kemwork (Kotlin) |
|---|---|
| `actor` (isolated mutable state) | `Mutex` guarding the state (`InMemoryCache`, `OAuthAuthorizationProvider`, `NetworkClient`) |
| `async`/`await` | `suspend` functions |
| `Task<Bool, Never>` (coalesced refresh) | `Deferred<Boolean>` stored under a `Mutex` |
| `AsyncStream<T>` | `StateFlow<T>` (`NetworkMonitor.updates`) |
| `Task.sleep` | `delay` |
| `Sendable` | immutable data classes / `Mutex`-guarded state |

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

The OkHttp engine lets kemwork reuse a mature ecosystem directly: `Interceptor`s (e.g. an APITrace
recorder), the 304-aware disk `Cache`, and `CertificatePinner` for pinning. A full Kotlin
Multiplatform build's only extra benefit would be an iOS target — already served by SwiftyNetwork —
at the cost of `expect/actual` for pinning, connectivity, caching, and tracing. The modules are
layered so a future KMP lift remains possible.

## Zero dependency injection

Like SwiftyNetwork (plain initializers + a `Configuration`), kemwork ships **no DI framework
dependency**. Consumers construct objects directly or wire them with their own DI (Hilt/Koin).
`NetworkClient.shared` offers a process-wide default.

## Testing

`MockEngine` (via the `:testing` module) drives `NetworkClient` with no sockets. Robolectric covers
`NetworkMonitor`. The build enforces a JaCoCo line-coverage gate on the published modules.
Unit tests run on a Java 21 toolchain (Robolectric + compileSdk 36 require it).
