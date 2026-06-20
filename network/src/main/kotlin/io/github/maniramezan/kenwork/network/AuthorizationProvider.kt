package io.github.maniramezan.kenwork.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Supplies (and refreshes) the authorization applied to requests that don't carry their own.
 * Mirrors SwiftyNetwork's `AuthorizationProvider`.
 */
public interface AuthorizationProvider {
    /** The authorization to attach to the next request. */
    public suspend fun currentAuthorization(): AuthorizationType

    /**
     * Attempts to refresh the credential after a `401`. Returns `true` if a fresh credential is
     * now available (and the request should be retried), `false` otherwise.
     */
    public suspend fun refreshAuthorizationIfNeeded(): Boolean
}

/**
 * An [AuthorizationProvider] that holds an OAuth bearer access token and refreshes it via
 * [refreshTokenHandler]. Mirrors SwiftyNetwork's `OAuthAuthorizationProvider`.
 *
 * Concurrent refreshes are **coalesced**: if several requests 401 at once, [refreshTokenHandler]
 * runs exactly once and all callers await the same result — the Kotlin analog of SwiftyNetwork's
 * in-flight refresh `Task`, and of Novalingo's sign-in mutex choke point.
 *
 * @param initialAccessToken the token to start with.
 * @param refreshTokenHandler mints a fresh access token, or returns `null` if refresh failed.
 */
public class OAuthAuthorizationProvider(
    initialAccessToken: String,
    private val refreshTokenHandler: suspend () -> String?,
) : AuthorizationProvider {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var accessToken: String = initialAccessToken
    private var inFlight: Deferred<Boolean>? = null

    /** The current access token. */
    public suspend fun currentAccessToken(): String = mutex.withLock { accessToken }

    override suspend fun currentAuthorization(): AuthorizationType = AuthorizationType.Bearer(mutex.withLock { accessToken })

    override suspend fun refreshAuthorizationIfNeeded(): Boolean {
        val deferred =
            mutex.withLock {
                inFlight ?: scope.async { performRefresh() }.also { inFlight = it }
            }
        return try {
            deferred.await()
        } finally {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    private suspend fun performRefresh(): Boolean {
        val newToken = refreshTokenHandler() ?: return false
        mutex.withLock { accessToken = newToken }
        return true
    }
}
