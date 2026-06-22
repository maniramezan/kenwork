package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.Serializable

@Serializable
data class Sample(
    val id: Int,
    val name: String,
)

class TestEndpoint(
    override val path: String,
    override val method: HttpMethod = HttpMethod.GET,
    override val baseUrl: String = "https://api.test",
    override val queryItems: List<Pair<String, String>>? = null,
    override val headers: Map<String, String>? = null,
    override val authorization: AuthorizationType = AuthorizationType.None,
    override val body: ByteArray? = null,
) : NetworkEndpoint

class TestAuthProvider(
    private var token: String?,
    private val refreshedToken: String? = "fresh",
    private val refreshSucceeds: Boolean = true,
) : AuthorizationProvider {
    var refreshCount = 0
        private set

    override suspend fun currentAuthorization(): AuthorizationType = token?.let { AuthorizationType.Bearer(it) } ?: AuthorizationType.None

    override suspend fun refreshAuthorizationIfNeeded(): Boolean {
        refreshCount++
        return if (refreshSucceeds && refreshedToken != null) {
            token = refreshedToken
            true
        } else {
            false
        }
    }
}

class RecordingListener : NetworkEventListener {
    val events = mutableListOf<NetworkEvent>()

    override fun onEvent(event: NetworkEvent) {
        events.add(event)
    }
}

fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

@Suppress("LongParameterList")
fun testClient(
    authorizationProvider: AuthorizationProvider? = null,
    maxAuthRefreshAttempts: Int = 1,
    maxTransientRetries: Int = 0,
    retryNonIdempotent: Boolean = false,
    retryBackoffBaseMillis: Long = 0,
    eventListener: NetworkEventListener? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): NetworkClient =
    NetworkClient(
        NetworkClientConfiguration(
            authorizationProvider = authorizationProvider,
            maxAuthRefreshAttempts = maxAuthRefreshAttempts,
            maxTransientRetries = maxTransientRetries,
            retryNonIdempotent = retryNonIdempotent,
            retryBackoffBaseMillis = retryBackoffBaseMillis,
            retryDelayMillis = 0,
            engine = MockEngine(handler),
            eventListener = eventListener,
        ),
    )
