package io.github.maniramezan.kemwork.testing

import io.github.maniramezan.kemwork.network.AuthorizationProvider
import io.github.maniramezan.kemwork.network.DefaultKemworkJson
import io.github.maniramezan.kemwork.network.NetworkClient
import io.github.maniramezan.kemwork.network.NetworkClientConfiguration
import io.github.maniramezan.kemwork.network.NetworkEventListener
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json

/**
 * Builds a [NetworkClient] backed by a Ktor `MockEngine` driven by [handler]. Intended for unit
 * tests of code that depends on kemwork — no real sockets are opened.
 *
 * [retryDelayMillis] defaults to `0` so 401-refresh-retry tests don't actually sleep.
 */
public fun mockNetworkClient(
    json: Json = DefaultKemworkJson,
    authorizationProvider: AuthorizationProvider? = null,
    maxAuthRefreshAttempts: Int = 1,
    retryDelayMillis: Long = 0,
    eventListener: NetworkEventListener? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): NetworkClient =
    NetworkClient(
        NetworkClientConfiguration(
            json = json,
            authorizationProvider = authorizationProvider,
            maxAuthRefreshAttempts = maxAuthRefreshAttempts,
            retryDelayMillis = retryDelayMillis,
            engine = MockEngine(handler),
            eventListener = eventListener,
        ),
    )

/** Responds with a JSON [body] and the given [status]. */
public fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData =
    respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
