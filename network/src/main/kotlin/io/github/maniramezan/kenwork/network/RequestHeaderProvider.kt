package io.github.maniramezan.kenwork.network

/**
 * Supplies extra request headers computed fresh for each attempt — the hook for trace-context
 * propagation headers (W3C `traceparent`/`tracestate`, `baggage`, or an equivalent from any other
 * tracing system) derived from whatever context is active right now.
 *
 * Unlike [NetworkEndpoint.headers], which is a fixed value on the endpoint instance, [headersFor] is
 * called immediately before each attempt is sent, so it can reflect state set up for that specific
 * attempt — e.g. a span started by a [RequestInterceptor] around the same `attempt` index, whose
 * tracing library now considers that span "current".
 *
 * Headers returned here are applied after [NetworkEndpoint.headers], so they augment rather than
 * remove endpoint-defined headers; on a name collision, the value from this provider wins.
 */
public fun interface RequestHeaderProvider {
    /**
     * @param endpoint the endpoint being called.
     * @param attempt the 0-based attempt index (0 = first try), matching [NetworkEvent.attempt] and
     *   the `attempt` passed to [RequestInterceptor.intercept] for the same attempt.
     * @return headers to add to this attempt's request, or an empty map for none.
     */
    public fun headersFor(
        endpoint: NetworkEndpoint,
        attempt: Int,
    ): Map<String, String>
}
