package io.github.maniramezan.kenwork.network

import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private const val MILLIS_PER_SECOND = 1_000L

/** Returns [response] when it is 2xx; otherwise drains it and throws the matching [NetworkError]. */
internal suspend fun validateOrThrow(response: HttpResponse): HttpResponse {
    val code = response.status.value
    if (HttpStatus.isSuccess(code)) return response
    val bytes = response.drainBody()
    throw when (code) {
        HttpStatus.UNAUTHORIZED -> NetworkError.Unauthorized
        HttpStatus.FORBIDDEN -> NetworkError.Forbidden
        HttpStatus.NOT_FOUND -> NetworkError.NotFound
        else -> NetworkError.ServerError(code, bytes, parseRetryAfterMillis(response.headers))
    }
}

/**
 * Decodes a validated response into [responseType]. `Unit` and [EmptyResponse] never touch the
 * body decoder, so bodyless responses such as `204 No Content` succeed for both.
 */
internal suspend fun decodeBody(
    response: HttpResponse,
    responseType: TypeInfo,
): Any? {
    when (responseType.type) {
        Unit::class -> {
            response.drainBody()
            return Unit
        }
        EmptyResponse::class -> {
            response.drainBody()
            return EmptyResponse
        }
    }
    return try {
        response.call.body(responseType)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw NetworkError.DecodingFailed(error)
    }
}

/** Reads and discards the body so the connection can be reused; returns it when readable. */
internal suspend fun HttpResponse.drainBody(): ByteArray? = runCatching { readRawBytes() }.getOrNull()

/**
 * Parses a `Retry-After` header (delta-seconds or an RFC 1123 HTTP date) into a non-negative
 * delay in milliseconds, or `null` when absent or malformed.
 */
internal fun parseRetryAfterMillis(
    headers: Headers,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
): Long? {
    val raw = headers[HttpHeaders.RetryAfter]?.trim() ?: return null
    val seconds = raw.toLongOrNull()
    if (seconds != null) {
        return when {
            seconds <= 0 -> 0
            seconds > Long.MAX_VALUE / MILLIS_PER_SECOND -> Long.MAX_VALUE
            else -> seconds * MILLIS_PER_SECOND
        }
    }
    return runCatching {
        val target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        (target - currentTimeMillis()).coerceAtLeast(0)
    }.getOrNull()
}

/**
 * Maps a failure onto the closed [NetworkError] set. Response decoding is wrapped separately (see
 * [decodeBody]), so a [SerializationException] reaching this point came from encoding the request.
 */
internal fun Throwable.toNetworkError(): NetworkError =
    when (this) {
        is NetworkError -> this
        is SerializationException -> NetworkError.EncodingFailed(this)
        is HttpRequestTimeoutException, is SocketTimeoutException -> NetworkError.Timeout
        is UnknownHostException, is ConnectException -> NetworkError.NoInternetConnection
        else -> NetworkError.Underlying(this)
    }
