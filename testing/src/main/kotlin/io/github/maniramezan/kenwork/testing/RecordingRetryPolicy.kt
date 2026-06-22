package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkError
import io.github.maniramezan.kenwork.network.RetryPolicy

/**
 * A [RetryPolicy] that records every decision and forwards to [delegate], so tests can assert how
 * many retries happened, for which errors, and with what delays. Defaults to wrapping a
 * [DefaultRetryPolicy].
 */
public class RecordingRetryPolicy(
    private val delegate: RetryPolicy = DefaultRetryPolicy(),
) : RetryPolicy {
    /** One recorded retry decision: the inputs and the [delayMillis] the [delegate] returned. */
    public data class Decision(
        public val attempt: Int,
        public val method: HttpMethod,
        public val error: NetworkError,
        public val delayMillis: Long?,
    )

    private val lock = Any()
    private val recorded = mutableListOf<Decision>()

    /** A snapshot of all decisions made so far, in order. */
    public val decisions: List<Decision>
        get() = synchronized(lock) { recorded.toList() }

    override fun retryDelayMillis(
        attempt: Int,
        method: HttpMethod,
        error: NetworkError,
    ): Long? {
        val delay = delegate.retryDelayMillis(attempt, method, error)
        synchronized(lock) { recorded.add(Decision(attempt, method, error, delay)) }
        return delay
    }
}
