package io.github.maniramezan.kenwork.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.http.HttpMethod as KtorHttpMethod

/**
 * Coroutine-based HTTP client built on Ktor + OkHttp. The Kotlin counterpart of SwiftyNetwork's
 * `NetworkClient`.
 *
 * Responsibilities, per request:
 * 1. Resolve authorization (endpoint-level, else the configured [AuthorizationProvider]).
 * 2. Execute the call.
 * 3. On `401`, refresh the credential and retry up to [NetworkClientConfiguration.maxAuthRefreshAttempts].
 * 4. Map non-2xx and transport failures to [NetworkError].
 * 5. Decode the success body into the requested type.
 *
 * Thread-safe: the mutable configuration/client pair is guarded by a [Mutex], and the active
 * client is reference-counted so [updateConfiguration] never closes a client mid-request.
 *
 * This class owns orchestration only (lifecycle, retry, auth). Engine construction lives in
 * `HttpClientFactory.kt`, response validation/decoding and exception mapping in
 * `ResponseHandling.kt`, and telemetry event construction in `NetworkTelemetry.kt`.
 */
public class NetworkClient(
    configuration: NetworkClientConfiguration = NetworkClientConfiguration(),
) : NetworkDataSource {
    /** A reference-counted [HttpClient] so reconfiguration can wait for in-flight calls to drain. */
    private class ClientHolder(
        val client: HttpClient,
    ) {
        var refCount: Int = 0
    }

    private val mutex = Mutex()
    private var configuration: NetworkClientConfiguration = configuration
    private var holder: ClientHolder = ClientHolder(buildHttpClient(configuration))
    private var closed: Boolean = false

    /**
     * Swaps in a new [NetworkClientConfiguration], rebuilding the underlying engine. The previous
     * client is closed only once its in-flight requests complete, so reconfiguring mid-request is
     * safe.
     */
    public suspend fun updateConfiguration(newConfiguration: NetworkClientConfiguration) {
        val toClose =
            mutex.withLock {
                check(!closed) { "NetworkClient is closed" }
                val replacement = ClientHolder(buildHttpClient(newConfiguration))
                val previous = holder
                configuration = newConfiguration
                holder = replacement
                previous.takeIf { it.refCount == 0 }
            }
        toClose?.client?.close()
    }

    /**
     * Closes the underlying HTTP client after in-flight requests complete. Once closed, this client
     * cannot make requests or accept configuration updates.
     *
     * Calling [close] more than once is safe.
     */
    public suspend fun close() {
        val toClose =
            mutex.withLock {
                if (closed) return
                closed = true
                holder.takeIf { it.refCount == 0 }
            }
        toClose?.client?.close()
    }

    override suspend fun <T> request(
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
    ): T {
        val acquired =
            mutex.withLock {
                check(!closed) { "NetworkClient is closed" }
                holder.also { it.refCount++ } to configuration
            }
        val activeHolder = acquired.first
        val config = acquired.second
        val startNs = System.nanoTime()
        try {
            @Suppress("UNCHECKED_CAST")
            return attemptWithRetry(activeHolder.client, config, endpoint, body, bodyType, responseType, startNs) as T
        } finally {
            withContext(NonCancellable) { release(activeHolder) }
        }
    }

    /** Runs the request, retrying transient failures per [NetworkClientConfiguration]. */
    private suspend fun attemptWithRetry(
        client: HttpClient,
        config: NetworkClientConfiguration,
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
        startNs: Long,
    ): Any? {
        var attempt = 0
        while (true) {
            val mapped: NetworkError =
                try {
                    val validated =
                        runIntercepted(config, endpoint, attempt) {
                            val response = executeWithAuth(client, config, endpoint, body, bodyType, attempt)
                            validateOrThrow(response)
                        }
                    val decoded = decodeBody(validated, responseType)
                    config.eventListener?.onEvent(successEvent(endpoint, validated.status.value, elapsedMs(startNs), attempt))
                    return decoded
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    error.toNetworkError()
                }
            val delayMillis = config.retryPolicy.retryDelayMillis(attempt + 1, endpoint.method, mapped)
            config.eventListener?.onEvent(
                mapped.toEvent(endpoint, elapsedMs(startNs), attempt, isFinalAttempt = delayMillis == null),
            )
            if (delayMillis == null) throw mapped
            attempt++
            awaitReachable(config)
            if (delayMillis > 0) delay(delayMillis)
        }
    }

    /** Runs [block] through [NetworkClientConfiguration.requestInterceptor], if one is configured. */
    private suspend fun <T> runIntercepted(
        config: NetworkClientConfiguration,
        endpoint: NetworkEndpoint,
        attempt: Int,
        block: suspend () -> T,
    ): T {
        val interceptor = config.requestInterceptor ?: return block()
        return interceptor.intercept(endpoint, attempt, block)
    }

    /** Parks (bounded) until connectivity returns, when a [ReachabilityGate] is configured. */
    private suspend fun awaitReachable(config: NetworkClientConfiguration) {
        val gate = config.reachabilityGate ?: return
        withTimeoutOrNull(config.reachabilityWaitMillis) { gate.awaitReachable() }
    }

    /** Releases a held client, closing it if it has been superseded and is now idle. */
    private suspend fun release(activeHolder: ClientHolder) {
        val toClose =
            mutex.withLock {
                activeHolder.refCount--
                activeHolder.takeIf { (closed || it !== holder) && it.refCount == 0 }
            }
        toClose?.client?.close()
    }

    private suspend fun executeWithAuth(
        client: HttpClient,
        config: NetworkClientConfiguration,
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        attempt: Int,
    ): HttpResponse {
        val provider = config.authorizationProvider
        val usesProviderAuthorization = endpoint.authorization == AuthorizationType.None
        var authAttempt = 0
        while (true) {
            val auth =
                endpoint.authorization
                    .takeUnless { it == AuthorizationType.None }
                    ?: provider?.currentAuthorization()
                    ?: AuthorizationType.None
            val response = performCall(client, endpoint, body, bodyType, auth, attempt, config.requestHeaderProvider)
            if (response.status != HttpStatusCode.Unauthorized) return response
            if (!usesProviderAuthorization || provider == null || authAttempt >= config.maxAuthRefreshAttempts) return response

            // Discard the 401 body so the connection is released before we retry.
            response.drainBody()
            if (!provider.refreshAuthorizationIfNeeded()) throw NetworkError.AuthorizationRefreshFailed
            authAttempt++
            if (config.retryDelayMillis > 0) delay(config.retryDelayMillis)
        }
    }

    private suspend fun performCall(
        client: HttpClient,
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        auth: AuthorizationType,
        attempt: Int,
        headerProvider: RequestHeaderProvider?,
    ): HttpResponse =
        client.request {
            method = KtorHttpMethod.parse(endpoint.method.value)
            url {
                takeFrom(endpoint.resolvedUrl())
                endpoint.queryItems?.forEach { (name, value) -> parameters.append(name, value) }
            }
            endpoint.headers?.forEach { (name, value) -> header(name, value) }
            headerProvider?.headersFor(endpoint, attempt)?.forEach { (name, value) ->
                // Replace rather than append, so a provider-supplied header (e.g. a fresh
                // `traceparent` per attempt) fully overrides one already set by the endpoint.
                headers.remove(name)
                header(name, value)
            }
            auth.applyTo(this)
            when {
                bodyType != null -> {
                    // ContentNegotiation serializes the typed body based on the request's
                    // Content-Type, so set JSON unless the endpoint already specified one.
                    if (headers[HttpHeaders.ContentType] == null) {
                        contentType(ContentType.Application.Json)
                    }
                    setBody(body, bodyType)
                }
                // Without its TypeInfo a typed body can't be serialized; sending the request
                // without it would silently drop the payload, so fail loudly instead.
                body != null -> throw NetworkError.EncodingFailed(
                    IllegalArgumentException("A request body of type ${body::class.simpleName} was passed without its bodyType"),
                )
                else -> endpoint.body?.let { setBody(it) }
            }
        }

    public companion object {
        /** A process-wide default client. Mirrors SwiftyNetwork's `NetworkClient.shared`. */
        public val shared: NetworkClient by lazy { NetworkClient() }

        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private fun elapsedMs(startNs: Long): Long = ((System.nanoTime() - startNs) / NANOS_PER_MILLI).coerceAtLeast(0L)
}
