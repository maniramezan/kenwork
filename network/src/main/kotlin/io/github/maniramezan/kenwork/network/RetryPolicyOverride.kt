package io.github.maniramezan.kenwork.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A coroutine context element that replaces [NetworkClientConfiguration.retryPolicy] for every
 * [NetworkClient] request made while it is in the context. Install it with [withRetryPolicy].
 *
 * Use it when a caller runs its own retry loop around requests and must not have the client retry
 * underneath it, which would multiply the attempts. `MutationQueue` uses it to make its retry policy
 * the only one for its calls. It applies to everything inside the block, including child coroutines.
 * [ApiClient] implementations other than [NetworkClient] may ignore it.
 *
 * @property retryPolicy the policy used instead of the client's configured one.
 */
public class RetryPolicyOverride(
    public val retryPolicy: RetryPolicy,
) : AbstractCoroutineContextElement(Key) {
    /** The context key for [RetryPolicyOverride]. */
    public companion object Key : CoroutineContext.Key<RetryPolicyOverride>
}

/**
 * Runs [block] with [retryPolicy] in place of each [NetworkClient]'s configured retry policy, e.g.
 * `withRetryPolicy(RetryPolicy.None) { client.request<User>(endpoint) }` for exactly one attempt.
 */
public suspend fun <T> withRetryPolicy(
    retryPolicy: RetryPolicy,
    block: suspend CoroutineScope.() -> T,
): T = withContext(RetryPolicyOverride(retryPolicy), block)
