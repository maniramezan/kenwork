package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransientRetryTest {
    @Test
    fun `retries a 5xx then succeeds`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2) {
                    calls++
                    if (calls == 1) {
                        json("""{"error":"boom"}""", HttpStatusCode.InternalServerError)
                    } else {
                        json("""{"id":1,"name":"ada"}""")
                    }
                }
            assertEquals(Sample(1, "ada"), client.request<Sample>(TestEndpoint("x")))
            assertEquals(2, calls)
        }

    @Test
    fun `gives up after exhausting transient retries`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2) {
                    calls++
                    json("""{"error":"boom"}""", HttpStatusCode.InternalServerError)
                }
            assertFailsWith<NetworkError.ServerError> { client.request<Sample>(TestEndpoint("x")) }
            assertEquals(3, calls)
        }

    @Test
    fun `does not retry a non-idempotent POST by default`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2) {
                    calls++
                    json("{}", HttpStatusCode.ServiceUnavailable)
                }
            assertFailsWith<NetworkError.ServerError> {
                client.request<Sample>(TestEndpoint("x", method = HttpMethod.POST))
            }
            assertEquals(1, calls)
        }

    @Test
    fun `retries a non-idempotent POST when explicitly allowed`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2, retryNonIdempotent = true) {
                    calls++
                    if (calls == 1) json("{}", HttpStatusCode.BadGateway) else json("""{"id":2,"name":"ok"}""")
                }
            assertEquals(Sample(2, "ok"), client.request<Sample>(TestEndpoint("x", method = HttpMethod.POST)))
            assertEquals(2, calls)
        }

    @Test
    fun `does not retry a 4xx`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                testClient(maxTransientRetries = 2) {
                    calls++
                    json("{}", HttpStatusCode.NotFound)
                }
            assertFailsWith<NetworkError.NotFound> { client.request<Sample>(TestEndpoint("x")) }
            assertEquals(1, calls)
        }

    @Test
    fun `updateConfiguration keeps an in-flight request alive`(): Unit =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val client =
                NetworkClient(
                    NetworkClientConfiguration(
                        engine =
                            MockEngine {
                                entered.complete(Unit)
                                gate.await()
                                json("""{"id":1,"name":"first"}""")
                            },
                    ),
                )

            val request = async(Dispatchers.Default) { client.request<Sample>(TestEndpoint("x")) }
            entered.await()
            client.updateConfiguration(
                NetworkClientConfiguration(engine = MockEngine { json("""{"id":2,"name":"second"}""") }),
            )
            gate.complete(Unit)

            // The in-flight request still completes against the original (now-superseded) client.
            assertEquals(Sample(1, "first"), request.await())
        }
}
