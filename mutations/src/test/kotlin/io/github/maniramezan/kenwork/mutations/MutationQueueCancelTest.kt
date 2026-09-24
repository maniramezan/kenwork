package io.github.maniramezan.kenwork.mutations

import app.cash.turbine.test
import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.NetworkError
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutationQueueCancelTest {
    @Test
    fun `cancel resets statusFlow to null for the key`() =
        runTest {
            val queue = MutationQueue(apiClient = RecordingApiClient(), scope = backgroundScope)
            val key = MutationKey.of("like", "video", 1)

            queue.statusFlow(key).test {
                assertNull(awaitItem())
                queue.enqueue(key, SetLikeState(1), LikeBody(true))
                assertEquals(MutationStatus.Pending, awaitItem())
                assertEquals(MutationStatus.Succeeded, awaitItem())

                queue.cancel(key)
                // null is the "nothing active" sentinel — same as pre-enqueue.
                assertNull(awaitItem())
            }
        }

    @Test
    fun `cancel stops a mutation that is waiting in retry backoff`() =
        runTest {
            val apiClient = RecordingApiClient { _, _ -> throw NetworkError.NoInternetConnection }
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    // Long backoff so cancel clearly lands while the worker is parked.
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 5, backoffBaseMillis = 60_000, retryNonIdempotent = true),
                )
            val key = MutationKey.of("like", "video", 2)

            queue.enqueue(key, SetLikeState(2), LikeBody(true))
            runCurrent() // first attempt fails; worker parks in 60 s backoff.

            assertEquals(MutationStatus.Retrying::class, queue.statusFlow(key).value!!::class)

            queue.cancel(key)
            runCurrent()

            // After cancellation the status is null and only one attempt was made.
            assertNull(queue.statusFlow(key).value)
            assertEquals(1, apiClient.calls.size)
        }

    @Test
    fun `cancel removes the persisted record from the store`() =
        runTest {
            val apiClient = RecordingApiClient { _, _ -> throw NetworkError.NoInternetConnection }
            val store = InMemoryMutationStore()
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    store = store,
                    defaultRetryPolicy = DefaultRetryPolicy(maxRetries = 5, backoffBaseMillis = 60_000, retryNonIdempotent = true),
                )
            val key = MutationKey.of("like", "video", 3)

            queue.enqueue(key, SetLikeState(3), LikeBody(true), codec = SetLikeStateCodec)
            runCurrent() // parks in retry backoff.

            assertEquals(1, store.loadAll().size)

            queue.cancel(key)
            runCurrent()

            assertTrue(store.loadAll().isEmpty(), "persisted record must be removed on cancel")
        }

    @Test
    fun `cancel on a key that was never enqueued is a no-op`() =
        runTest {
            val queue = MutationQueue(apiClient = RecordingApiClient(), scope = backgroundScope)
            val key = MutationKey.of("like", "video", 99)

            // Should not throw or crash.
            queue.cancel(key)

            assertNull(queue.statusFlow(key).value)
        }

    @Test
    fun `a new enqueue after cancel works normally`() =
        runTest {
            val queue = MutationQueue(apiClient = RecordingApiClient(), scope = backgroundScope)
            val key = MutationKey.of("like", "video", 4)

            queue.enqueue(key, SetLikeState(4), LikeBody(true))
            settle()
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)

            queue.cancel(key)
            settle()
            assertNull(queue.statusFlow(key).value)

            // A fresh enqueue after cancel should work and report Succeeded.
            queue.enqueue(key, SetLikeState(4), LikeBody(false))
            settle()
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)
        }
}
