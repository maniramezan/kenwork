package io.github.maniramezan.kenwork.network

/**
 * Wraps a single request attempt, giving observability tooling — tracing in particular — a place to
 * start a span/segment with an accurate timestamp, propagate the currently active context, and
 * record the outcome, all without [NetworkClient] depending on any specific tracing API.
 *
 * [NetworkClient] invokes [intercept] once per attempt: [attempt] is the same 0-based index carried
 * by [NetworkEvent.attempt], so a retried request calls [intercept] once per attempt rather than once
 * for the whole logical request. This is a deliberate choice — a span per attempt mirrors common HTTP
 * client tracing conventions (retries become sibling spans, each attempt gets its own accurate
 * start/end) and matches what [NetworkEvent] already reports, whereas a single "whole request" span
 * is something a consumer can just as easily create around their own call to
 * [NetworkClient.request]/[NetworkClient.execute] — no hook is needed for that case. `attempt` is
 * still recorded, so a consumer can set it as a span attribute (e.g. `http.request.resend_count`) to
 * link retries as part of one logical operation.
 *
 * A typical implementation starts a span, calls [proceed] (recording an exception if it throws), and
 * ends the span in a `finally` block — see the "OpenTelemetry" recipe in `docs/cookbook.md` for a
 * worked example. Combine with [RequestHeaderProvider] when the attempt also needs to propagate
 * context onto the outgoing request (e.g. a W3C `traceparent` header): start the span here, before
 * calling [proceed], so it's the "current" span by the time [RequestHeaderProvider.headersFor] runs
 * for the same attempt.
 *
 * This is a plain (not `fun`) interface — [intercept]'s type parameter makes it ineligible for SAM
 * conversion, so implement it with an object expression, e.g.
 * `object : RequestInterceptor { override suspend fun <T> intercept(...) = ... }`.
 */
public interface RequestInterceptor {
    /**
     * Called once per attempt, wrapping the work that executes it. Implementations must call
     * [proceed] to actually perform the attempt (typically exactly once) and return its result;
     * failing to call it — or swallowing an exception it throws — will silently break the request.
     *
     * @param endpoint the endpoint being called.
     * @param attempt the 0-based attempt index (0 = first try), matching [NetworkEvent.attempt].
     * @param proceed performs the attempt. Propagates the attempt's exception (mapped to
     *   [NetworkError] or otherwise) if it fails.
     */
    public suspend fun <T> intercept(
        endpoint: NetworkEndpoint,
        attempt: Int,
        proceed: suspend () -> T,
    ): T
}
