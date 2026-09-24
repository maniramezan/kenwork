package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.LogLevel
import io.github.maniramezan.kenwork.network.NetworkClient
import io.github.maniramezan.kenwork.network.NetworkClientConfiguration
import io.github.maniramezan.kenwork.network.NetworkEventListener
import io.github.maniramezan.kenwork.network.OAuthAuthorizationProvider
import io.github.maniramezan.kenwork.network.ReachabilityGate
import io.ktor.client.engine.HttpClientEngine

// Step 2 — build one NetworkClient per credential scope and share it.

/**
 * Builds the app's authenticated client:
 * - a `401` triggers one coalesced [TokenSource.refresh] and a single replay;
 * - timeouts, lost connectivity, `429` and `5xx` on idempotent calls are retried with jittered
 *   backoff, and a [reachability] gate (e.g. `NetworkMonitor.asReachabilityGate()`) parks those
 *   retries while offline;
 * - every attempt is reported to [telemetry].
 *
 * Remember to `close()` both the client and [OAuthAuthorizationProvider] when the session ends.
 *
 * @param engine leave `null` in production (an OkHttp engine is built); tests pass a `MockEngine`.
 */
fun createAuthenticatedClient(
    tokens: TokenSource,
    telemetry: NetworkEventListener? = null,
    reachability: ReachabilityGate? = null,
    engine: HttpClientEngine? = null,
): NetworkClient =
    NetworkClient(
        NetworkClientConfiguration(
            authorizationProvider =
                OAuthAuthorizationProvider(
                    initialAccessToken = tokens.accessToken,
                    refreshTokenHandler = tokens::refresh,
                ),
            retryPolicy = DefaultRetryPolicy(maxRetries = 2),
            reachabilityGate = reachability,
            eventListener = telemetry,
            // Body logging can leak credentials and personal data; keep it off outside debug builds.
            logLevel = LogLevel.WARNING,
            engine = engine,
        ),
    )
