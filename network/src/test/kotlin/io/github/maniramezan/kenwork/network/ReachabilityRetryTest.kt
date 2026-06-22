package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ReachabilityRetryTest {
    @Test
    fun `waits on the reachability gate before each retry`(): Unit =
        runBlocking {
            var gateCalls = 0
            var calls = 0
            val client =
                NetworkClient(
                    NetworkClientConfiguration(
                        retryPolicy = DefaultRetryPolicy(maxRetries = 2, backoffBaseMillis = 0),
                        reachabilityGate = ReachabilityGate { gateCalls++ },
                        reachabilityWaitMillis = 1_000,
                        retryDelayMillis = 0,
                        engine =
                            MockEngine {
                                calls++
                                if (calls == 1) {
                                    json("{}", HttpStatusCode.InternalServerError)
                                } else {
                                    json("""{"id":1,"name":"ada"}""")
                                }
                            },
                    ),
                )

            assertEquals(Sample(1, "ada"), client.request<Sample>(TestEndpoint("x")))
            assertEquals(2, calls)
            assertEquals(1, gateCalls)
        }

    @Test
    fun `bounded wait lets the retry proceed when connectivity never returns`(): Unit =
        runBlocking {
            var calls = 0
            val client =
                NetworkClient(
                    NetworkClientConfiguration(
                        retryPolicy = DefaultRetryPolicy(maxRetries = 1, backoffBaseMillis = 0),
                        // A gate that never resolves; the bounded wait must still let the retry run.
                        reachabilityGate = ReachabilityGate { awaitCancellation() },
                        reachabilityWaitMillis = 50,
                        retryDelayMillis = 0,
                        engine =
                            MockEngine {
                                calls++
                                if (calls == 1) {
                                    json("{}", HttpStatusCode.ServiceUnavailable)
                                } else {
                                    json("""{"id":1,"name":"ada"}""")
                                }
                            },
                    ),
                )

            assertEquals(Sample(1, "ada"), client.request<Sample>(TestEndpoint("x")))
            assertEquals(2, calls)
        }
}
