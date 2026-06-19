# SwiftyNetwork ↔ kemwork parity

kemwork is the Kotlin counterpart of [SwiftyNetwork](https://github.com/maniramezan/SwiftyNetwork).
This table maps each public type/behavior between the two libraries. It also serves as a checklist
for the parity tests in `:network`, `:cache`, and `:repository`.

| Concept | SwiftyNetwork (Swift) | kemwork (Kotlin) |
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
| Logging | `Logger` / `LogLevel` | `KemworkLogger` / `LogLevel` / `LogSink` |

## Intentional differences

- **Telemetry:** kemwork adds `NetworkEventListener`/`NetworkEvent` (ported from Novalingo's
  network telemetry) so analytics can be wired without a DI framework.
- **Timestamps:** kemwork uses epoch-millisecond `Long` where SwiftyNetwork uses `Date`.
- **Reified requests:** kemwork exposes `request<T>()` / `request<B, T>(body)` extensions instead of
  passing a `responseType:` argument.
- **`AnyCache`:** kept for symmetry, but Kotlin generics don't require Swift-style type erasure.
