package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryPolicyTest {
    @Test
    fun `retries a 429 by default`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2) {
                    calls++
                    if (calls == 1) json("{}", HttpStatusCode.TooManyRequests) else json("""{"id":1,"name":"ada"}""")
                }
            assertEquals(Sample(1, "ada"), client.request<Sample>(TestEndpoint("x")))
            assertEquals(2, calls)
        }

    @Test
    fun `parses Retry-After seconds into the ServerError hint`(): Unit =
        runBlocking {
            val seen = mutableListOf<Long?>()
            val policy =
                RetryPolicy { attempt, _, error ->
                    seen += (error as? NetworkError.ServerError)?.retryAfterMillis
                    if (attempt <= 1) 0L else null
                }
            var calls = 0
            val client =
                testClient(retryPolicy = policy) {
                    calls++
                    if (calls == 1) {
                        respond(
                            "{}",
                            HttpStatusCode.ServiceUnavailable,
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.RetryAfter to listOf("2"),
                            ),
                        )
                    } else {
                        json("""{"id":1,"name":"ada"}""")
                    }
                }
            assertEquals(Sample(1, "ada"), client.request<Sample>(TestEndpoint("x")))
            assertEquals(listOf<Long?>(2_000L), seen)
        }

    @Test
    fun `RetryPolicy None disables retries`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(retryPolicy = RetryPolicy.None) {
                    calls++
                    json("{}", HttpStatusCode.InternalServerError)
                }
            assertFailsWith<NetworkError.ServerError> { client.request<Sample>(TestEndpoint("x")) }
            assertEquals(1, calls)
        }

    @Test
    fun `a custom policy controls the retry count`(): Unit =
        runBlocking {
            var calls = 0
            // Allow exactly one retry, regardless of error.
            val client =
                testClient(retryPolicy = RetryPolicy { attempt, _, _ -> if (attempt <= 1) 0L else null }) {
                    calls++
                    json("{}", HttpStatusCode.InternalServerError)
                }
            assertFailsWith<NetworkError.ServerError> { client.request<Sample>(TestEndpoint("x")) }
            assertEquals(2, calls)
        }

    @Test
    fun `emits one telemetry event per attempt`(): Unit =
        runBlocking {
            val listener = RecordingListener()
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2, eventListener = listener) {
                    calls++
                    if (calls == 1) json("{}", HttpStatusCode.InternalServerError) else json("""{"id":1,"name":"ada"}""")
                }
            client.request<Sample>(TestEndpoint("x"))

            assertEquals(2, listener.events.size)
            assertEquals("ServerError", listener.events[0].errorType)
            assertEquals(0, listener.events[0].attempt)
            assertTrue(listener.events[0].isRetryable)
            assertTrue(listener.events[1].isSuccess)
            assertEquals(1, listener.events[1].attempt)
        }
}
