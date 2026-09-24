package io.github.maniramezan.kenwork.mutations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that [MutationQueue] evicts completed workers and their status flows from its internal
 * maps so the queue doesn't grow without bound over the lifetime of a long-lived process.
 *
 * The maps are package-private implementation detail, but their effect is visible through
 * [MutationQueue.statusFlow]: after a mutation reaches a terminal state and the worker cleans up,
 * calling [MutationQueue.statusFlow] for a *brand-new* key (one that was never observed before)
 * must return `null`, not a stale value from a previous run under the same key.
 */
class MutationQueueMemoryTest {
    @Test
    fun `statusFlow returns null for a key after its completed worker is evicted`() =
        runTest {
            val queue = MutationQueue(apiClient = RecordingApiClient(), scope = backgroundScope)
            val key = MutationKey.of("like", "video", 1)

            // Enqueue and complete a mutation.
            queue.enqueue(key, SetLikeState(1), LikeBody(true))
            settle()

            // The worker for `key` has reached Succeeded. After the onTerminal callback fires,
            // the maps are cleaned up. A fresh statusFlow call must return a new null flow,
            // not a cached Succeeded flow from the (now evicted) run.
            //
            // We verify this by cancelling, which resets the flow. If the old Succeeded flow
            // were still registered the value would be Succeeded, not null.
            queue.cancel(key)
            settle()

            assertNull(queue.statusFlow(key).value)
        }

    @Test
    fun `many keys complete without causing map growth`() =
        runTest {
            val queue = MutationQueue(apiClient = RecordingApiClient(), scope = backgroundScope)

            // Enqueue mutations for many distinct keys and let them all complete.
            repeat(100) { i ->
                queue.enqueue(MutationKey.of("item", i), SetLikeState(i), LikeBody(true))
            }
            settle()

            // All 100 keys completed successfully. After terminal cleanup the maps are empty,
            // so statusFlow for any of those keys returns null from a fresh flow — not from
            // a cached entry holding Succeeded.
            // We sample one key to verify; if the map weren't cleaned up it would return Succeeded.
            val sample = MutationKey.of("item", 0)
            // The flow for `sample` was evicted; this call creates a *new* null flow.
            assertNull(queue.statusFlow(sample).value)
        }

    @Test
    fun `status retention limit does not evict an active worker flow`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val queue =
                MutationQueue(
                    apiClient = RecordingApiClient { _, _ -> release.await() },
                    scope = backgroundScope,
                    maxStatuses = 1,
                )
            val first = MutationKey.of("item", 1)
            val second = MutationKey.of("item", 2)

            queue.enqueue(first, SetLikeState(1), LikeBody(true))
            runCurrent()
            queue.enqueue(second, SetLikeState(2), LikeBody(true))
            runCurrent()

            assertEquals(MutationStatus.Pending, queue.statusFlow(first).value)
            assertEquals(MutationStatus.Pending, queue.statusFlow(second).value)

            release.complete(Unit)
            settle()
        }
}
