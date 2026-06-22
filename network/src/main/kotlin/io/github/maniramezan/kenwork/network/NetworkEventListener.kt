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
 */
public data class NetworkEvent(
    public val endpointId: String,
    public val method: String,
    public val durationMs: Long,
    public val statusCode: Int? = null,
    public val errorType: String? = null,
    public val isRetryable: Boolean = false,
    public val attempt: Int = 0,
) {
    /** Whether this event represents a successful (2xx) request. */
    public val isSuccess: Boolean get() = errorType == null && statusCode in 200..299
}

/** Receives [NetworkEvent]s emitted by [NetworkClient]. Wire this to your analytics pipeline. */
public fun interface NetworkEventListener {
    public fun onEvent(event: NetworkEvent)
}
