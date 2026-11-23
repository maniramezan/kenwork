package io.github.maniramezan.kenwork.network

/** HTTP status codes the library branches on. Kept in one place so policies can't drift apart. */
internal object HttpStatus {
    const val OK: Int = 200
    const val LAST_SUCCESS: Int = 299
    const val UNAUTHORIZED: Int = 401
    const val FORBIDDEN: Int = 403
    const val NOT_FOUND: Int = 404
    const val TOO_MANY_REQUESTS: Int = 429
    const val SERVER_ERROR: Int = 500

    fun isSuccess(code: Int): Boolean = code in OK..LAST_SUCCESS

    /** The statuses [DefaultRetryPolicy] treats as transient by default: `429` and any `5xx`. */
    fun isDefaultRetryable(code: Int): Boolean = code == TOO_MANY_REQUESTS || code >= SERVER_ERROR
}

/**
 * Whether this failure is transient — a timeout, lost connectivity, or a [NetworkError.ServerError]
 * whose status satisfies [isRetryableStatus] (by default `429` or any `5xx`). Shared by
 * [DefaultRetryPolicy], telemetry ([NetworkEvent.isRetryable]), and repositories serving stale data,
 * so they can't disagree about what "transient" means. Use it for the same decision in app code.
 *
 * A [NetworkError.AuthorizationUnavailable] is transient when the provider failed only because the
 * device lost connectivity: its cause, or a cause further down the chain, is a timeout or lost
 * connection. Anything else means the user has no usable credential, which retrying cannot fix.
 */
public fun NetworkError.isTransient(isRetryableStatus: (Int) -> Boolean = HttpStatus::isDefaultRetryable): Boolean =
    when (this) {
        NetworkError.Timeout, NetworkError.NoInternetConnection -> true
        is NetworkError.ServerError -> isRetryableStatus(statusCode)
        is NetworkError.AuthorizationUnavailable -> cause?.transportFailure() != null
        else -> false
    }

/** Bounds the cause walk so a cyclic chain cannot loop forever. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * The transient transport failure behind this throwable, if any: itself or a cause further down the
 * chain that maps to [NetworkError.Timeout] or [NetworkError.NoInternetConnection].
 */
internal fun Throwable.transportFailure(): NetworkError? =
    generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }
        .take(MAX_CAUSE_DEPTH)
        .map { it.toNetworkError() }
        .firstOrNull { it == NetworkError.Timeout || it == NetworkError.NoInternetConnection }
