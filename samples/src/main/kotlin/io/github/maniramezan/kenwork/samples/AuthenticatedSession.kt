package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.LogLevel
import io.github.maniramezan.kenwork.network.NetworkClient
import io.github.maniramezan.kenwork.network.NetworkClientConfiguration
import io.github.maniramezan.kenwork.network.NetworkEventListener
import io.github.maniramezan.kenwork.network.OAuthAuthorizationProvider
import io.github.maniramezan.kenwork.network.ReachabilityGate
import io.ktor.client.engine.HttpClientEngine

/** Owns the authenticated client and its refresh scope for one signed-in session. */
class AuthenticatedSession internal constructor(
    val client: NetworkClient,
    private val authorizationProvider: OAuthAuthorizationProvider,
) {
    /** Cancels refreshes and prevents new requests. Safe to call repeatedly on sign-out. */
    suspend fun close() {
        authorizationProvider.close()
        client.close()
    }
}

/**
 * Builds the app's authenticated session:
 * - a `401` triggers one coalesced [TokenSource.refresh] and a single replay;
 * - timeouts, lost connectivity, `429` and `5xx` on idempotent calls are retried with jittered
 *   backoff, and a [reachability] gate (e.g. `NetworkMonitor.asReachabilityGate()`) parks those
 *   retries while offline;
 * - every attempt is reported to [telemetry].
 *
 * Share [AuthenticatedSession.client] and close the session when it ends so both the client and
 * its [OAuthAuthorizationProvider] release their resources.
 *
 * @param engine leave `null` in production (an OkHttp engine is built); tests pass a `MockEngine`.
 */
fun createAuthenticatedSession(
    tokens: TokenSource,
    telemetry: NetworkEventListener? = null,
    reachability: ReachabilityGate? = null,
    engine: HttpClientEngine? = null,
): AuthenticatedSession {
    val provider =
        OAuthAuthorizationProvider(
            initialAccessToken = tokens.accessToken,
            refreshTokenHandler = tokens::refresh,
        )
    val client =
        NetworkClient(
            NetworkClientConfiguration(
                authorizationProvider = provider,
                retryPolicy = DefaultRetryPolicy(maxRetries = 2),
                reachabilityGate = reachability,
                eventListener = telemetry,
                logLevel = LogLevel.WARNING,
                engine = engine,
            ),
        )
    return AuthenticatedSession(client, provider)
}
