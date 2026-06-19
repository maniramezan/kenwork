package io.github.maniramezan.kemwork.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import io.ktor.client.plugins.logging.LogLevel as KtorLogLevel
import io.ktor.client.plugins.logging.Logger as KtorLogger
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
 * Thread-safe: the mutable [configuration]/[httpClient] pair is guarded by a [Mutex].
 */
public class NetworkClient(
    configuration: NetworkClientConfiguration = NetworkClientConfiguration(),
) : NetworkDataSource {
    private val mutex = Mutex()
    private var configuration: NetworkClientConfiguration = configuration
    private var httpClient: HttpClient = buildClient(configuration)

    /** Swaps in a new [NetworkClientConfiguration], rebuilding the underlying engine. */
    public suspend fun updateConfiguration(newConfiguration: NetworkClientConfiguration) {
        mutex.withLock {
            httpClient.close()
            configuration = newConfiguration
            httpClient = buildClient(newConfiguration)
        }
    }

    override suspend fun <T> request(
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
    ): T {
        val client: HttpClient
        val config: NetworkClientConfiguration
        mutex.withLock {
            client = httpClient
            config = configuration
        }
        val startNs = System.nanoTime()
        try {
            val response = executeWithAuth(client, config, endpoint, body, bodyType)
            val validated = validateOrThrow(response)
            config.eventListener?.onEvent(
                NetworkEvent(
                    endpointId = endpoint.endpointId(),
                    method = endpoint.method.value,
                    durationMs = elapsedMs(startNs),
                    statusCode = validated.status.value,
                ),
            )
            @Suppress("UNCHECKED_CAST")
            return decodeBody(validated, responseType) as T
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkError) {
            config.eventListener?.onEvent(error.toEvent(endpoint, elapsedMs(startNs)))
            throw error
        } catch (error: Throwable) {
            val mapped = mapException(error)
            config.eventListener?.onEvent(mapped.toEvent(endpoint, elapsedMs(startNs)))
            throw mapped
        }
    }

    private suspend fun executeWithAuth(
        client: HttpClient,
        config: NetworkClientConfiguration,
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
    ): HttpResponse {
        val provider = config.authorizationProvider
        var attempt = 0
        while (true) {
            val auth =
                endpoint.authorization
                    .takeUnless { it == AuthorizationType.None }
                    ?: provider?.currentAuthorization()
                    ?: AuthorizationType.None
            val response = performCall(client, endpoint, body, bodyType, auth)
            if (response.status != HttpStatusCode.Unauthorized) return response
            if (provider == null || attempt >= config.maxAuthRefreshAttempts) return response

            // Discard the 401 body so the connection is released before we retry.
            runCatching { response.readRawBytes() }
            if (!provider.refreshAuthorizationIfNeeded()) throw NetworkError.AuthorizationRefreshFailed
            attempt++
            if (config.retryDelayMillis > 0) delay(config.retryDelayMillis)
        }
    }

    private suspend fun performCall(
        client: HttpClient,
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        auth: AuthorizationType,
    ): HttpResponse =
        client.request {
            method = KtorHttpMethod.parse(endpoint.method.value)
            url {
                takeFrom(endpoint.resolvedUrl())
                endpoint.queryItems?.forEach { (name, value) -> parameters.append(name, value) }
            }
            endpoint.headers?.forEach { (name, value) -> header(name, value) }
            auth.applyTo(this)
            when {
                bodyType != null -> {
                    // ContentNegotiation serializes the typed body based on the request's
                    // Content-Type, so set JSON unless the endpoint already specified one.
                    if (headers[io.ktor.http.HttpHeaders.ContentType] == null) {
                        contentType(io.ktor.http.ContentType.Application.Json)
                    }
                    setBody(body, bodyType)
                }
                endpoint.body != null -> setBody(endpoint.body!!)
            }
        }

    private suspend fun validateOrThrow(response: HttpResponse): HttpResponse {
        val code = response.status.value
        if (code in HTTP_OK..HTTP_LAST_SUCCESS) return response
        val bytes = runCatching { response.readRawBytes() }.getOrNull()
        throw when (code) {
            HTTP_UNAUTHORIZED -> NetworkError.Unauthorized
            HTTP_FORBIDDEN -> NetworkError.Forbidden
            HTTP_NOT_FOUND -> NetworkError.NotFound
            else -> NetworkError.ServerError(code, bytes)
        }
    }

    private suspend fun decodeBody(
        response: HttpResponse,
        responseType: TypeInfo,
    ): Any? {
        if (responseType.type == Unit::class) {
            runCatching { response.readRawBytes() }
            return Unit
        }
        return try {
            response.call.body(responseType)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw NetworkError.DecodingFailed(error)
        }
    }

    private fun mapException(error: Throwable): NetworkError =
        when (error) {
            is HttpRequestTimeoutException, is SocketTimeoutException -> NetworkError.Timeout
            is UnknownHostException, is ConnectException -> NetworkError.NoInternetConnection
            else -> NetworkError.Underlying(error)
        }

    private fun buildClient(config: NetworkClientConfiguration): HttpClient {
        val common: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false
            install(ContentNegotiation) { json(config.json) }
            install(HttpTimeout) { requestTimeoutMillis = config.timeoutMillis }
            install(Logging) {
                level = config.logLevel.toKtorLogLevel()
                logger =
                    object : KtorLogger {
                        override fun log(message: String) {
                            KemworkLogger.debug(message, LogCategory.NETWORK)
                        }
                    }
            }
        }
        val explicitEngine = config.engine
        return if (explicitEngine != null) {
            HttpClient(explicitEngine, common)
        } else {
            HttpClient(OkHttp) {
                common()
                engine {
                    config.engineInterceptors.forEach { addInterceptor(it) }
                    config {
                        config.okHttpCache?.let { cache(it) }
                        config.sslPinning?.let { certificatePinner(it.toCertificatePinner()) }
                    }
                }
            }
        }
    }

    public companion object {
        /** A process-wide default client. Mirrors SwiftyNetwork's `NetworkClient.shared`. */
        public val shared: NetworkClient by lazy { NetworkClient() }

        private const val HTTP_OK = 200
        private const val HTTP_LAST_SUCCESS = 299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val NANOS_PER_MILLI = 1_000_000L
    }

    private fun elapsedMs(startNs: Long): Long = ((System.nanoTime() - startNs) / NANOS_PER_MILLI).coerceAtLeast(0L)
}

private fun LogLevel.toKtorLogLevel(): KtorLogLevel =
    when (this) {
        LogLevel.OFF -> KtorLogLevel.NONE
        LogLevel.ERROR, LogLevel.WARNING, LogLevel.INFO -> KtorLogLevel.INFO
        LogLevel.DEBUG -> KtorLogLevel.BODY
    }

private fun NetworkError.toEvent(
    endpoint: NetworkEndpoint,
    durationMs: Long,
): NetworkEvent {
    val statusCode =
        when (this) {
            NetworkError.Unauthorized -> 401
            NetworkError.Forbidden -> 403
            NetworkError.NotFound -> 404
            is NetworkError.ServerError -> statusCode
            else -> null
        }
    val retryable =
        this is NetworkError.Timeout ||
            this is NetworkError.NoInternetConnection ||
            (this is NetworkError.ServerError && statusCode != null && statusCode >= 500)
    return NetworkEvent(
        endpointId = endpoint.endpointId(),
        method = endpoint.method.value,
        durationMs = durationMs,
        statusCode = statusCode,
        errorType = this::class.simpleName,
        isRetryable = retryable,
    )
}

private val UUID_REGEX =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

/** A low-cardinality identifier for telemetry: path with numeric/uuid segments normalized. */
internal fun NetworkEndpoint.endpointId(): String {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return "root"
    return segments.joinToString("/") { segment ->
        when {
            segment.all { it.isDigit() } -> ":id"
            UUID_REGEX.matches(segment.lowercase()) -> ":uuid"
            else -> segment
        }
    }
}
