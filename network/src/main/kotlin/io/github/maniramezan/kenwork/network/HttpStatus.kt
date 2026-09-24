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
 * whose status satisfies [isRetryableStatus]. Shared by [DefaultRetryPolicy] and telemetry so the
 * two can't disagree about what "retryable" means.
 */
internal fun NetworkError.isTransient(isRetryableStatus: (Int) -> Boolean = HttpStatus::isDefaultRetryable): Boolean =
    when (this) {
        NetworkError.Timeout, NetworkError.NoInternetConnection -> true
        is NetworkError.ServerError -> isRetryableStatus(statusCode)
        else -> false
    }
