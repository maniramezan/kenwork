package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.AuthorizationProvider
import io.github.maniramezan.kenwork.network.DefaultKenworkJson
import io.github.maniramezan.kenwork.network.NetworkClient
import io.github.maniramezan.kenwork.network.NetworkClientConfiguration
import io.github.maniramezan.kenwork.network.NetworkEventListener
import io.github.maniramezan.kenwork.network.ReachabilityGate
import io.github.maniramezan.kenwork.network.RetryPolicy
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
 * tests of code that depends on kenwork — no real sockets are opened.
 *
 * [retryDelayMillis] defaults to `0` so 401-refresh-retry tests don't actually sleep, and
 * [retryPolicy] defaults to [RetryPolicy.None] so requests aren't transparently retried unless a
 * test opts in (pass a [io.github.maniramezan.kenwork.network.DefaultRetryPolicy] or custom policy).
 *
 * @param retryPolicy retry behavior; off by default for deterministic tests.
 * @param reachabilityGate optional connectivity gate exercised on retries (see [FakeReachabilityGate]).
 */
@Suppress("LongParameterList")
public fun mockNetworkClient(
    json: Json = DefaultKenworkJson,
    authorizationProvider: AuthorizationProvider? = null,
    maxAuthRefreshAttempts: Int = 1,
    retryDelayMillis: Long = 0,
    retryPolicy: RetryPolicy = RetryPolicy.None,
    reachabilityGate: ReachabilityGate? = null,
    eventListener: NetworkEventListener? = null,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): NetworkClient =
    NetworkClient(
        NetworkClientConfiguration(
            json = json,
            authorizationProvider = authorizationProvider,
            maxAuthRefreshAttempts = maxAuthRefreshAttempts,
            retryPolicy = retryPolicy,
            reachabilityGate = reachabilityGate,
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
