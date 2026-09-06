# Security boundaries

## Credentials and destinations

Use HTTPS endpoints and keep the destination host under application control. A configured
authorization provider supplies credentials to requests whose authorization is `None`; `None`
does **not** opt an endpoint out of that provider. Use a separate unauthenticated client for public
or untrusted destinations. Do not construct authenticated endpoint hosts from untrusted input.

Prefer one source for each authentication header. Explicit endpoint authorization takes priority
over the client provider. Avoid copying credentials into URLs, cache keys, mutation payloads, or
diagnostic strings. Base64 Basic credentials are encoding, not encryption.

`network-core` disables redirects by default. When enabling redirects or using `NetworkClient`,
review the actual engine's handling of custom credential headers and destination changes.
`SslPinningConfiguration` applies only to the built-in OkHttp path; an explicit engine owns its
TLS configuration. The `okHttpConfig` hook can override builder settings, so it is trusted code.

## Logging and telemetry

`NetworkClientConfiguration.logLevel = DEBUG` enables body logging, routed through
`KenworkLogger.debug` when the global logger permits it. Bodies and URLs can contain secrets and
personal data. Keep production verbosity low and sanitize data before forwarding it to external
logging or analytics systems. Do not log raw `NetworkError.ServerError.body` by default.

Numeric and UUID path segments are normalized in telemetry, but arbitrary path text is retained.
Avoid sensitive path segments and high-cardinality identifiers. Custom log sinks, interceptors,
and header providers are application code and must apply their own redaction rules.

## Persistence and retries

`FileSystemCache` hashes filenames but writes encoded values as plaintext. Use application-private
storage, choose what is safe to persist, and provide encryption in the storage/codec layer when
required. Namespace cache keys by account or tenant and clear account-specific caches on sign-out.
Repository requests coalesce by cache key, so keys must also distinguish endpoint parameters and
authorization context whenever the returned data differs.

Mutation persistence requires both a codec and a durable `MutationStore`; the default store is
in-memory. Avoid persisting bearer tokens in records; resolve current authorization at execution.
Coordinate sign-out with the queue's scope and store so old-account work cannot run with a new
account's credentials.

Transient retries exclude POST/PATCH by default, while the mutation queue explicitly opts into
non-idempotent retry. Use server-side idempotency keys or operations that safely set a desired
state. A transport timeout cannot prove that a server did not apply a write. OAuth's 401 replay
is a separate path controlled by `maxAuthRefreshAttempts`.
