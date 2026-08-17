package io.github.maniramezan.kenwork.mutations

import app.cash.turbine.test
import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.NetworkError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MutationQueueStatusTest {
    @Test
    fun `statusFlow is null before anything is enqueued`() =
        runTest {
            val queue = MutationQueue(apiClient = RecordingApiClient(), scope = backgroundScope)
            assertNull(queue.statusFlow(MutationKey.of("unused")).value)
        }

    @Test
    fun `transitions Pending then Succeeded on a clean success`() =
        runTest {
            val apiClient = RecordingApiClient()
            val queue = MutationQueue(apiClient = apiClient, scope = backgroundScope)
            val key = MutationKey.of("like", "video", 1)

            queue.statusFlow(key).test {
                assertNull(awaitItem())
                queue.enqueue(key, SetLikeState(1), LikeBody(true))
                assertEquals(MutationStatus.Pending, awaitItem())
                assertEquals(MutationStatus.Succeeded, awaitItem())
            }
        }

    @Test
    fun `transitions Pending then Retrying then Succeeded when the first attempt fails transiently`() =
        runTest {
            val apiClient =
                RecordingApiClient { _, index ->
                    if (index == 0) throw NetworkError.ServerError(503, null)
                }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 2, backoffBaseMillis = 0, retryNonIdempotent = true),
                )
            val key = MutationKey.of("like", "video", 2)

            queue.statusFlow(key).test {
                assertNull(awaitItem())
                queue.enqueue(key, SetLikeState(2), LikeBody(true))
                assertEquals(MutationStatus.Pending, awaitItem())
                val retrying = awaitItem()
                assertIs<MutationStatus.Retrying>(retrying)
                assertEquals(1, retrying.attempt)
                assertEquals(MutationStatus.Succeeded, awaitItem())
            }
        }

    @Test
    fun `transitions Pending then Failed when the retry policy gives up`() =
        runTest {
            val apiClient = RecordingApiClient { _, _ -> throw NetworkError.ServerError(500, null) }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    // Non-idempotent POST, no opt-in: fails on the very first attempt.
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 2, backoffBaseMillis = 0),
                )
            val key = MutationKey.of("like", "video", 3)

            queue.statusFlow(key).test {
                assertNull(awaitItem())
                queue.enqueue(key, SetLikeState(3), LikeBody(true))
                assertEquals(MutationStatus.Pending, awaitItem())
                val failed = awaitItem()
                assertIs<MutationStatus.Failed>(failed)
                assertIs<NetworkError.ServerError>(failed.error)
            }
        }

    @Test
    fun `superseded in-flight request cannot overwrite replacement Pending status`() =
        runTest {
            val firstMayComplete = CompletableDeferred<Unit>()
            val secondMayComplete = CompletableDeferred<Unit>()
            val apiClient =
                RecordingApiClient { _, index ->
                    if (index == 0) firstMayComplete.await() else secondMayComplete.await()
                }
            val queue = MutationQueue(apiClient = apiClient, scope = backgroundScope)
            val key = MutationKey.of("like", "video", 4)

            queue.enqueue(key, SetLikeState(4), LikeBody(true))
            runCurrent()
            queue.enqueue(key, SetLikeState(4), LikeBody(false))

            firstMayComplete.complete(Unit)
            runCurrent()

            assertEquals(MutationStatus.Pending, queue.statusFlow(key).value)
            secondMayComplete.complete(Unit)
            settle()
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)
        }
}
