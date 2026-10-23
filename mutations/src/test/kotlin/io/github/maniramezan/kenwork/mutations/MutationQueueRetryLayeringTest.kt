package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.testing.FakeReachabilityGate
import io.github.maniramezan.kenwork.testing.mockNetworkClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

private data class PutLikeState(
    val videoId: Int,
) : NetworkEndpoint {
    override val baseUrl = "https://api.test"
    override val path = "videos/$videoId/like"
    override val method = HttpMethod.PUT
}

class MutationQueueRetryLayeringTest {
    @Test
    fun `the queue's retry policy is the only retry layer, even over a retrying client`() =
        runTest {
            val calls = AtomicInteger(0)
            val gate = FakeReachabilityGate()
            // The client would retry an idempotent PUT twice on its own.
            val client =
                mockNetworkClient(retryPolicy = DefaultRetryPolicy(backoffBaseMillis = 0), reachabilityGate = gate) {
                    calls.incrementAndGet()
                    respond("", HttpStatusCode.ServiceUnavailable)
                }
            val queue =
                MutationQueue(
                    apiClient = client,
                    scope = backgroundScope,
                    defaultRetryPolicy = DefaultRetryPolicy(retryNonIdempotent = true, backoffBaseMillis = 0),
                )
            val key = MutationKey.of("like", "video", 9)

            queue.enqueue(key, PutLikeState(9), LikeBody(true))
            queue.statusFlow(key).first { it is MutationStatus.Failed }

            // One initial attempt plus the queue's two retries — not 3 x 3 = 9.
            assertEquals(3, calls.get())
            assertEquals(3, gate.awaitCount)
        }
}
