package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class NotSerializable(
    val value: Int,
)

class ResponseHandlingTest {
    @Test
    fun `EmptyResponse decodes a 204 with no body`(): Unit =
        runBlocking {
            val client = testClient { respond("", HttpStatusCode.NoContent) }
            assertSame(EmptyResponse, client.request<EmptyResponse>(TestEndpoint("videos/1", method = HttpMethod.DELETE)))
        }

    @Test
    fun `EmptyResponse ignores a non-empty body`(): Unit =
        runBlocking {
            val client = testClient { json("""{"unexpected":true}""") }
            assertSame(EmptyResponse, client.request<EmptyResponse>(TestEndpoint("videos/1")))
        }

    @Test
    fun `endpointId normalizes uuids of any version and case`() {
        // v7 (time-ordered) ids are increasingly common as primary keys.
        assertEquals("videos/:uuid", TestEndpoint("videos/01890a5d-ac96-774b-bcce-b302099a8057").endpointId())
        assertEquals("videos/:uuid/likes", TestEndpoint("videos/550E8400-E29B-41D4-A716-446655440000/likes").endpointId())
        assertEquals("videos/not-a-uuid", TestEndpoint("videos/not-a-uuid").endpointId())
    }

    @Test
    fun `parses Retry-After delta seconds`() {
        assertEquals(3_000L, parseRetryAfterMillis(headersOf(HttpHeaders.RetryAfter, " 3 ")))
        assertEquals(0L, parseRetryAfterMillis(headersOf(HttpHeaders.RetryAfter, "-5")))
        assertEquals(Long.MAX_VALUE, parseRetryAfterMillis(headersOf(HttpHeaders.RetryAfter, "${Long.MAX_VALUE}")))
        assertNull(parseRetryAfterMillis(headersOf()))
        assertNull(parseRetryAfterMillis(headersOf(HttpHeaders.RetryAfter, "soon")))
    }

    @Test
    fun `parses Retry-After HTTP dates relative to now`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val inTenSeconds = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(10).atOffset(ZoneOffset.UTC))
        val inThePast = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(10).atOffset(ZoneOffset.UTC))
        assertEquals(10_000L, parseRetryAfterMillis(headersOf(HttpHeaders.RetryAfter, inTenSeconds)) { now.toEpochMilli() })
        assertEquals(0L, parseRetryAfterMillis(headersOf(HttpHeaders.RetryAfter, inThePast)) { now.toEpochMilli() })
    }

    @Test
    fun `maps transport exceptions onto NetworkError`() {
        assertSame(NetworkError.Timeout, SocketTimeoutException().toNetworkError())
        assertSame(NetworkError.NoInternetConnection, UnknownHostException().toNetworkError())
        assertSame(NetworkError.NoInternetConnection, ConnectException().toNetworkError())
        val passthrough = NetworkError.NotFound
        assertSame(passthrough, passthrough.toNetworkError())
        assertIs<NetworkError.Underlying>(IOException("reset").toNetworkError())
    }

    @Test
    fun `httpStatusCode exposes the status carried by an error`() {
        assertEquals(401, NetworkError.Unauthorized.httpStatusCode)
        assertEquals(403, NetworkError.Forbidden.httpStatusCode)
        assertEquals(404, NetworkError.NotFound.httpStatusCode)
        assertEquals(503, NetworkError.ServerError(503, null).httpStatusCode)
        assertNull(NetworkError.Timeout.httpStatusCode)
    }

    @Test
    fun `classifies transient failures consistently`() {
        assertTrue(NetworkError.Timeout.isTransient())
        assertTrue(NetworkError.NoInternetConnection.isTransient())
        assertTrue(NetworkError.ServerError(429, null).isTransient())
        assertTrue(NetworkError.ServerError(503, null).isTransient())
        assertFalse(NetworkError.ServerError(400, null).isTransient())
        assertFalse(NetworkError.NotFound.isTransient())
        assertFalse(NetworkError.ServerError(503, null).isTransient { false })
    }

    @Test
    fun `a typed body without its TypeInfo fails instead of being silently dropped`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient {
                    calls++
                    json("{}")
                }
            assertFailsWith<NetworkError.EncodingFailed> {
                client.request<Unit>(TestEndpoint("videos", method = HttpMethod.POST), Sample(1, "a"), null, typeInfo<Unit>())
            }
            assertEquals(0, calls, "nothing may be sent without the body")
        }

    @Test
    fun `an unserializable body surfaces as EncodingFailed`(): Unit =
        runBlocking {
            val client = testClient { json("{}") }
            val error =
                assertFailsWith<NetworkError.EncodingFailed> {
                    client.request<NotSerializable, Unit>(TestEndpoint("videos", method = HttpMethod.POST), NotSerializable(1))
                }
            assertIs<SerializationException>(error.cause)
        }
}
