package io.github.maniramezan.kenwork.network

import kotlin.random.Random

/**
 * Decides whether (and after how long) a failed request should be retried.
 *
 * Implement this to plug in custom retry behavior. [NetworkClient] consults it after every failed
 * attempt: returning a non-negative delay schedules another attempt after that many milliseconds;
 * returning `null` stops retrying and propagates the [error].
 *
 * The default, [DefaultRetryPolicy], retries transient failures with jittered exponential backoff.
 * Use [RetryPolicy.None] to disable retries entirely.
 */
public fun interface RetryPolicy {
    /**
     * The delay before the upcoming retry, or `null` to give up.
     *
     * @param attempt the 1-based number of the retry being considered (1 = first retry).
     * @param method the request's HTTP method (e.g. to avoid retrying non-idempotent calls).
     * @param error the failure from the previous attempt; a [NetworkError.ServerError] carries the
     *   status code and any parsed `Retry-After` hint.
     */
    public fun retryDelayMillis(
        attempt: Int,
        method: HttpMethod,
        error: NetworkError,
    ): Long?

    public companion object {
        /** A policy that never retries. */
        public val None: RetryPolicy = RetryPolicy { _, _, _ -> null }
    }
}

/**
 * The default [RetryPolicy]: retries transient failures — timeouts, lost connectivity, `429`, and
 * `5xx` — with exponential backoff and full jitter, honoring a server `Retry-After` hint when
 * present. Non-idempotent methods (`POST`/`PATCH`) are not retried unless [retryNonIdempotent].
 *
 * @param maxRetries maximum number of retries (beyond the initial attempt). `0` disables retry.
 * @param retryNonIdempotent allow retrying `POST`/`PATCH`; off by default to avoid duplicate writes.
 * @param backoffBaseMillis base delay; retry *n* waits a random value in `[0, base * 2^(n-1)]`.
 * @param backoffMaxMillis upper bound on a single backoff (and on an honored `Retry-After`).
 * @param isRetryableStatus predicate selecting retryable HTTP status codes; defaults to `429` + `5xx`.
 * @param random jitter source, injectable for deterministic tests.
 */
public class DefaultRetryPolicy(
    public val maxRetries: Int = DEFAULT_MAX_RETRIES,
    public val retryNonIdempotent: Boolean = false,
    public val backoffBaseMillis: Long = DEFAULT_BACKOFF_BASE_MILLIS,
    public val backoffMaxMillis: Long = DEFAULT_BACKOFF_MAX_MILLIS,
    public val isRetryableStatus: (Int) -> Boolean = { it == STATUS_TOO_MANY_REQUESTS || it >= STATUS_SERVER_ERROR },
    private val random: Random = Random.Default,
) : RetryPolicy {
    override fun retryDelayMillis(
        attempt: Int,
        method: HttpMethod,
        error: NetworkError,
    ): Long? {
        val allowed =
            attempt <= maxRetries &&
                (method.isIdempotent || retryNonIdempotent) &&
                isRetryable(error)
        return if (allowed) delayFor(attempt, error) else null
    }

    private fun isRetryable(error: NetworkError): Boolean =
        when (error) {
            NetworkError.Timeout, NetworkError.NoInternetConnection -> true
            is NetworkError.ServerError -> isRetryableStatus(error.statusCode)
            else -> false
        }

    private fun delayFor(
        attempt: Int,
        error: NetworkError,
    ): Long {
        val retryAfter = (error as? NetworkError.ServerError)?.retryAfterMillis
        if (retryAfter != null) return retryAfter.coerceIn(0, backoffMaxMillis)
        val shift = (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        val ceiling = (backoffBaseMillis shl shift).coerceAtMost(backoffMaxMillis)
        return random.nextLong(ceiling + 1)
    }

    public companion object {
        public const val DEFAULT_MAX_RETRIES: Int = 2
        public const val DEFAULT_BACKOFF_BASE_MILLIS: Long = 500
        public const val DEFAULT_BACKOFF_MAX_MILLIS: Long = 10_000
        private const val STATUS_TOO_MANY_REQUESTS = 429
        private const val STATUS_SERVER_ERROR = 500
        private const val MAX_BACKOFF_SHIFT = 16
    }
}
