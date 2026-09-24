package io.github.maniramezan.kenwork.network

/** Matches a canonical 8-4-4-4-12 hex UUID of any version (including time-ordered v6/v7). */
private val UUID_REGEX =
    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

/** A low-cardinality identifier for telemetry: path with numeric/uuid segments normalized. */
internal fun NetworkEndpoint.endpointId(): String {
    val segments = path.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return "root"
    return segments.joinToString("/") { segment ->
        when {
            segment.all { it.isDigit() } -> ":id"
            UUID_REGEX.matches(segment) -> ":uuid"
            else -> segment
        }
    }
}

/** The event reported for a successful attempt. */
internal fun successEvent(
    endpoint: NetworkEndpoint,
    statusCode: Int,
    durationMs: Long,
    attempt: Int,
): NetworkEvent =
    NetworkEvent(
        endpointId = endpoint.endpointId(),
        method = endpoint.method.value,
        durationMs = durationMs,
        statusCode = statusCode,
        attempt = attempt,
    )

/**
 * The event reported for a failed attempt.
 *
 * [NetworkEvent.isRetryable] is a coarse, [RetryPolicy]-agnostic classification of "does this look
 * like a transient failure" (see [isTransient]). It intentionally does not reflect a specific
 * configured policy's exhaustion state (`maxRetries`) or custom `isRetryableStatus`, since those
 * answer "was this attempt retried", which [NetworkEvent.isFinalAttempt] already reports.
 */
internal fun NetworkError.toEvent(
    endpoint: NetworkEndpoint,
    durationMs: Long,
    attempt: Int,
    isFinalAttempt: Boolean,
): NetworkEvent =
    NetworkEvent(
        endpointId = endpoint.endpointId(),
        method = endpoint.method.value,
        durationMs = durationMs,
        statusCode = httpStatusCode,
        errorType = this::class.simpleName,
        isRetryable = isTransient(),
        attempt = attempt,
        isFinalAttempt = isFinalAttempt,
    )
