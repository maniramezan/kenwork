package io.github.maniramezan.kenwork.network

/**
 * A single observed network request outcome, for telemetry/analytics. The Kotlin counterpart of
 * Novalingo's `NetworkTelemetryEvent`, kept transport-agnostic so any analytics backend can
 * consume it.
 *
 * @property endpointId a normalized, low-cardinality endpoint identifier (e.g. `videos/:id`).
 * @property method the HTTP method.
 * @property durationMs wall-clock duration of the attempt(s).
 * @property statusCode the final HTTP status, or `null` if the request never completed.
 * @property errorType a coarse error classification, or `null` on success.
 * @property isRetryable whether the failure is considered transient.
 * @property attempt the 0-based attempt index this event describes (0 = first try). A request that
 *   is retried emits one event per failed attempt followed by a final success/failure event.
 * @property isFinalAttempt whether this event is the last one for its logical request — `true` for
 *   a success, or for a failure the [RetryPolicy] declined to retry; `false` for a failed attempt
 *   that's about to be retried. Lets a metrics bridge emit exactly one request-duration observation
 *   per logical request (filter on `isFinalAttempt`) while still using every event, including
 *   non-final ones, for a per-attempt/retry-count counter.
 */
public data class NetworkEvent(
    public val endpointId: String,
    public val method: String,
    public val durationMs: Long,
    public val statusCode: Int? = null,
    public val errorType: String? = null,
    public val isRetryable: Boolean = false,
    public val attempt: Int = 0,
    public val isFinalAttempt: Boolean = true,
) {
    /** Whether this event represents a successful (2xx) request. */
    public val isSuccess: Boolean get() = errorType == null && statusCode in 200..299

    /**
     * Binary-compatibility shim: pre-0.4 consumers compiled against the constructor without
     * [isFinalAttempt] matched a JVM constructor (and default-argument bridge) with this exact
     * parameter list. Kept linkable for them via [DeprecationLevel.HIDDEN], which also keeps it
     * invisible to (and out of ambiguity with) new source — always resolves to the primary
     * constructor above, with [isFinalAttempt] defaulting to `true`.
     */
    @Deprecated(
        "Binary-compatibility shim for pre-0.4 callers; use the primary constructor.",
        level = DeprecationLevel.HIDDEN,
    )
    public constructor(
        endpointId: String,
        method: String,
        durationMs: Long,
        statusCode: Int? = null,
        errorType: String? = null,
        isRetryable: Boolean = false,
        attempt: Int = 0,
    ) : this(endpointId, method, durationMs, statusCode, errorType, isRetryable, attempt, true)
}

/** Receives [NetworkEvent]s emitted by [NetworkClient]. Wire this to your analytics pipeline. */
public fun interface NetworkEventListener {
    public fun onEvent(event: NetworkEvent)
}
