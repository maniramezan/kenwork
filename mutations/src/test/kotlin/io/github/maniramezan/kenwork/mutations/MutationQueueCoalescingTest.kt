package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.NetworkError
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MutationQueueCoalescingTest {
    @Test
    fun `rapid toggle collapses to a single call for the latest desired state`() =
        runTest {
            val apiClient = RecordingApiClient()
            val queue = MutationQueue(apiClient = apiClient, scope = backgroundScope)
            val key = MutationKey.of("like", "video", 42)

            // Toggle like/unlike/like/unlike/like faster than any call could land.
            repeat(5) { i ->
                queue.enqueue(key, SetLikeState(42), LikeBody(liked = i % 2 == 0))
            }
            settle()

            assertEquals(1, apiClient.calls.size)
            assertEquals(LikeBody(liked = true), apiClient.calls.single().body)
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)
        }

    @Test
    fun `a new enqueue for the same key cancels an in-progress retry backoff`() =
        runTest {
            val apiClient =
                RecordingApiClient { _, index ->
                    if (index == 0) throw NetworkError.ServerError(503, null)
                }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    // A long backoff so the coalescing enqueue below clearly lands mid-wait.
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 2, backoffBaseMillis = 60_000, retryNonIdempotent = true),
                )
            val key = MutationKey.of("like", "video", 7)

            queue.enqueue(key, SetLikeState(7), LikeBody(true))
            runCurrent() // let the first (failing) attempt run and enter its retry backoff.
            assertEquals(MutationStatus.Retrying::class, queue.statusFlow(key).value!!::class)

            // Coalesce a new desired state in while the first attempt is still backing off.
            queue.enqueue(key, SetLikeState(7), LikeBody(false))
            settle()

            // Only 2 network calls total: the failed first attempt, then the coalesced retry —
            // the 60s backoff never actually elapses because supersession cancels the wait.
            assertEquals(2, apiClient.calls.size)
            assertEquals(LikeBody(false), apiClient.calls.last().body)
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)
        }

    @Test
    fun `different keys are not coalesced together`() =
        runTest {
            val apiClient = RecordingApiClient()
            val queue = MutationQueue(apiClient = apiClient, scope = backgroundScope)

            queue.enqueue(MutationKey.of("like", "video", 1), SetLikeState(1), LikeBody(true))
            queue.enqueue(MutationKey.of("like", "video", 2), SetLikeState(2), LikeBody(true))
            settle()

            assertEquals(2, apiClient.calls.size)
        }
}
