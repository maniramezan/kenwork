package io.github.maniramezan.kenwork.network

/**
 * The error type surfaced by [NetworkClient]. Mirrors SwiftyNetwork's `NetworkError`.
 *
 * Every failure a request can produce is mapped onto one of these cases, so callers can branch
 * on a closed set instead of inspecting raw transport exceptions.
 */
public sealed class NetworkError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The endpoint produced a malformed URL. */
    public data class InvalidUrl(
        val url: String,
    ) : NetworkError("Invalid URL: $url")

    /** The response could not be interpreted as HTTP. */
    public data object InvalidResponse : NetworkError("Invalid response")

    /** The response body was missing or unreadable. */
    public data object InvalidData : NetworkError("Invalid data")

    /**
     * A non-2xx status outside the specifically-mapped cases below, carrying the raw [body] and the
     * parsed `Retry-After` hint in milliseconds ([retryAfterMillis]), when the server sent one.
     */
    public class ServerError(
        public val statusCode: Int,
        public val body: ByteArray?,
        public val retryAfterMillis: Long? = null,
    ) : NetworkError("Server error: $statusCode")

    /** HTTP 401. */
    public data object Unauthorized : NetworkError("Unauthorized")

    /** HTTP 403. */
    public data object Forbidden : NetworkError("Forbidden")

    /** HTTP 404. */
    public data object NotFound : NetworkError("Not found")

    /** The request exceeded its timeout. */
    public data object Timeout : NetworkError("Request timed out")

    /** The device appears to be offline / the host was unreachable. */
    public data object NoInternetConnection : NetworkError("No internet connection")

    /** The response body failed to decode into the requested type. */
    public class DecodingFailed(
        cause: Throwable,
    ) : NetworkError("Decoding failed", cause)

    /** The request body failed to encode. */
    public class EncodingFailed(
        cause: Throwable,
    ) : NetworkError("Encoding failed", cause)

    /** A 401 was received and the authorization provider could not refresh the credential. */
    public data object AuthorizationRefreshFailed : NetworkError("Authorization refresh failed")

    /** Any other transport-level failure. */
    public class Underlying(
        cause: Throwable,
    ) : NetworkError(cause.message ?: "Underlying network error", cause)
}
