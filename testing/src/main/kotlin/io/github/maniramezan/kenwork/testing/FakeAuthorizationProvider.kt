package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.AuthorizationProvider
import io.github.maniramezan.kenwork.network.AuthorizationType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A scriptable [AuthorizationProvider] for tests. Tracks how many times a refresh was requested
 * and can simulate refresh success or failure.
 *
 * @param initialToken the token returned before any refresh, or `null` for no authorization.
 * @param refreshedToken the token to switch to on a successful refresh.
 * @param refreshSucceeds whether [refreshAuthorizationIfNeeded] reports success.
 */
public class FakeAuthorizationProvider(
    initialToken: String? = "token",
    private val refreshedToken: String? = "refreshed-token",
    private val refreshSucceeds: Boolean = true,
) : AuthorizationProvider {
    private val mutex = Mutex()

    /** The token currently handed out. */
    public var currentToken: String? = initialToken
        private set

    /** How many times [refreshAuthorizationIfNeeded] has been invoked. */
    public var refreshCount: Int = 0
        private set

    override suspend fun currentAuthorization(): AuthorizationType =
        currentToken?.let { AuthorizationType.Bearer(it) } ?: AuthorizationType.None

    override suspend fun refreshAuthorizationIfNeeded(): Boolean =
        mutex.withLock {
            refreshCount++
            if (refreshSucceeds && refreshedToken != null) {
                currentToken = refreshedToken
                true
            } else {
                false
            }
        }
}
