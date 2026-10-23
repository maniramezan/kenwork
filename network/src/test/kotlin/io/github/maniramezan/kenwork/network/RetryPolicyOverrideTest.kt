package io.github.maniramezan.kenwork.network

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryPolicyOverrideTest {
    @Test
    fun `an override disables the configured retries for calls made inside it`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2) {
                    calls++
                    json("""{"error":"boom"}""", HttpStatusCode.ServiceUnavailable)
                }
            assertFailsWith<NetworkError.ServerError> {
                withRetryPolicy(RetryPolicy.None) { client.request<Sample>(TestEndpoint("x")) }
            }
            assertEquals(1, calls)
        }

    @Test
    fun `an override can also add retries the configuration does not have`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(retryPolicy = RetryPolicy.None) {
                    calls++
                    json("""{"error":"boom"}""", HttpStatusCode.ServiceUnavailable)
                }
            assertFailsWith<NetworkError.ServerError> {
                withRetryPolicy(DefaultRetryPolicy(maxRetries = 1, backoffBaseMillis = 0)) {
                    client.request<Sample>(TestEndpoint("x"))
                }
            }
            assertEquals(2, calls)
        }

    @Test
    fun `calls outside the override keep the configured policy`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2) {
                    calls++
                    json("""{"error":"boom"}""", HttpStatusCode.ServiceUnavailable)
                }
            withRetryPolicy(RetryPolicy.None) { }
            assertFailsWith<NetworkError.ServerError> { client.request<Sample>(TestEndpoint("x")) }
            assertEquals(3, calls)
        }
}
