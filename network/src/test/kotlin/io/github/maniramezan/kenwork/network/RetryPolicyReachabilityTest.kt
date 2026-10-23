package io.github.maniramezan.kenwork.network

import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryPolicyReachabilityTest {
    @Test
    fun `external retries can opt into a bounded wait before a single attempt`() =
        runTest {
            var waits = 0
            var sends = 0
            val engine =
                MockEngine {
                    sends++
                    json("")
                }
            val waitMillis = 1_000L
            val client =
                NetworkClient(
                    NetworkClientConfiguration(
                        engine = engine,
                        reachabilityGate =
                            ReachabilityGate {
                                waits++
                                awaitCancellation()
                            },
                        reachabilityWaitMillis = waitMillis,
                    ),
                )

            try {
                withRetryPolicy(RetryPolicy.None, awaitReachabilityBeforeRequest = true) {
                    client.request<Unit>(TestEndpoint("x"))
                }

                assertEquals(waitMillis, testScheduler.currentTime)
                assertEquals(1, waits)
                assertEquals(1, sends)
            } finally {
                client.close()
                engine.close()
            }
        }

    @Test
    fun `the original override does not wait before the initial request`() =
        runTest {
            var waits = 0
            val engine = MockEngine { json("") }
            val client =
                NetworkClient(
                    NetworkClientConfiguration(
                        engine = engine,
                        reachabilityGate =
                            ReachabilityGate {
                                waits++
                                awaitCancellation()
                            },
                    ),
                )

            try {
                withRetryPolicy(RetryPolicy.None) { client.request<Unit>(TestEndpoint("x")) }

                assertEquals(0, waits)
                assertEquals(0L, testScheduler.currentTime)
            } finally {
                client.close()
                engine.close()
            }
        }
}
