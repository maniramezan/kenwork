package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.HttpClientEngine
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** The default JSON codec: lenient and tolerant of unknown keys. */
public val DefaultKenworkJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Immutable configuration for a [NetworkClient]. Mirrors SwiftyNetwork's
 * `NetworkClientConfiguration`.
 *
 * @property json JSON codec used for (de)serialization.
 * @property authorizationProvider supplies/refreshes credentials for requests without their own.
 * @property maxAuthRefreshAttempts how many times to refresh-and-retry after a `401`.
 * @property retryPolicy decides whether/when to retry a failed request. Defaults to
 *   [DefaultRetryPolicy] (jittered exponential backoff for timeouts, lost connectivity, `429`, and
 *   `5xx`, honoring `Retry-After`). Supply your own or [RetryPolicy.None] to disable retries.
 * @property timeoutMillis per-request timeout.
 * @property retryDelayMillis delay between a successful refresh and the retry.
 * @property logLevel verbosity of the Ktor request logger.
 * @property engineInterceptors OkHttp interceptors (e.g. an APITrace recorder) added to the engine.
 * @property okHttpConfig escape hatch to configure the underlying [OkHttpClient.Builder] directly
 *   (e.g. to install a bootstrap that needs the builder). Ignored when [engine] is set.
 * @property okHttpCache optional OkHttp disk cache honoring backend `Cache-Control`/`ETag`.
 * @property sslPinning optional certificate pinning.
 * @property engine optional explicit Ktor engine; when set, the OkHttp-specific options above are
 *   ignored (used by tests to inject a `MockEngine`). When `null`, an OkHttp engine is built.
 * @property eventListener optional telemetry sink.
 */
public class NetworkClientConfiguration(
    public val json: Json = DefaultKenworkJson,
    public val authorizationProvider: AuthorizationProvider? = null,
    public val maxAuthRefreshAttempts: Int = 1,
    public val retryPolicy: RetryPolicy = DefaultRetryPolicy(),
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    public val logLevel: LogLevel = LogLevel.WARNING,
    public val engineInterceptors: List<Interceptor> = emptyList(),
    public val okHttpConfig: (OkHttpClient.Builder.() -> Unit)? = null,
    public val okHttpCache: Cache? = null,
    public val sslPinning: SslPinningConfiguration? = null,
    public val engine: HttpClientEngine? = null,
    public val eventListener: NetworkEventListener? = null,
) {
    public companion object {
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000
        public const val DEFAULT_RETRY_DELAY_MILLIS: Long = 1_000
    }
}
