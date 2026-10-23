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
 * @property awaitReachabilityBeforeRequest whether to await the client's configured connectivity
 *   gate before the initial attempt, bounded by [NetworkClientConfiguration.reachabilityWaitMillis].
 *   Useful when an outer retry loop owns retries but must preserve connectivity waiting.
 */
public class RetryPolicyOverride(
    public val retryPolicy: RetryPolicy,
    public val awaitReachabilityBeforeRequest: Boolean,
) : AbstractCoroutineContextElement(Key) {
    /** Creates an override that retains the normal connectivity wait only before client retries. */
    public constructor(retryPolicy: RetryPolicy) : this(retryPolicy, awaitReachabilityBeforeRequest = false)

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

/**
 * Runs [block] with [retryPolicy], optionally waiting for each request's configured connectivity
 * gate before its initial attempt. The wait uses that client's configured time bound, remains
 * cancellable, and is skipped when the client has no gate.
 *
 * Outer retry loops such as `MutationQueue` use [RetryPolicy.None] with
 * [awaitReachabilityBeforeRequest] enabled to retain connectivity waiting without nested retries.
 */
public suspend fun <T> withRetryPolicy(
    retryPolicy: RetryPolicy,
    awaitReachabilityBeforeRequest: Boolean,
    block: suspend CoroutineScope.() -> T,
): T = withContext(RetryPolicyOverride(retryPolicy, awaitReachabilityBeforeRequest), block)
