package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.DefaultRetryPolicy
import io.github.maniramezan.kenwork.network.NetworkError
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MutationQueueRestoreTest {
    private val key = MutationKey.of("like", "video", 42)

    @Test
    fun `a codec-backed mutation stays persisted while it is still retrying`() =
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

            queue.enqueue(key, SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec)
            runCurrent() // let the first attempt fail and enter its (long) retry backoff.

            val persisted = store.loadAll()
            assertEquals(1, persisted.size)
            assertEquals(SetLikeStateCodec.id, persisted.single().codecId)
        }

    @Test
    fun `a successful mutation is removed from the store`() =
        runTest {
            val apiClient = RecordingApiClient()
            val store = InMemoryMutationStore()
            val queue = MutationQueue(apiClient = apiClient, scope = backgroundScope, store = store)

            queue.enqueue(key, SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec)
            settle()

            assertTrue(store.loadAll().isEmpty())
        }

    @Test
    fun `restore replays a persisted record against a fresh MutationQueue instance`() =
        runTest {
            val store = InMemoryMutationStore()
            store.save(
                MutationRecord(
                    id = "record-1",
                    key = key.value,
                    codecId = SetLikeStateCodec.id,
                    payload = SetLikeStateCodec.encode(SetLikeState(42), LikeBody(true)),
                    enqueuedAtMillis = 0L,
                ),
            )

            val apiClient = RecordingApiClient()
            // Simulates a fresh process: a brand-new MutationQueue over the same durable store,
            // with the codec registered upfront so restore() can decode the leftover record.
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    store = store,
                    codecs = listOf(SetLikeStateCodec),
                )

            queue.restore()
            settle()

            assertEquals(1, apiClient.calls.size)
            val replayedEndpoint = apiClient.calls.single().endpoint as SetLikeState
            assertEquals(42, replayedEndpoint.videoId)
            assertEquals(LikeBody(true), apiClient.calls.single().body)
            assertEquals(MutationStatus.Succeeded, queue.statusFlow(key).value)
            assertTrue(store.loadAll().isEmpty())
        }

    @Test
    fun `restore skips records whose codec is not registered`() =
        runTest {
            val store = InMemoryMutationStore()
            store.save(
                MutationRecord(
                    id = "record-1",
                    key = key.value,
                    codecId = "unknown-codec",
                    payload = "irrelevant",
                    enqueuedAtMillis = 0L,
                ),
            )
            val apiClient = RecordingApiClient()
            val queue = MutationQueue(apiClient = apiClient, scope = backgroundScope, store = store)

            queue.restore()
            settle()

            assertEquals(0, apiClient.calls.size)
            // Left untouched so a later app version that registers the codec can still recover it.
            assertEquals(1, store.loadAll().size)
        }

    @Test
    fun `restore keeps only the newest persisted mutation for each key`() =
        runTest {
            val store = InMemoryMutationStore()
            val newest =
                MutationRecord(
                    id = "newest",
                    key = key.value,
                    codecId = SetLikeStateCodec.id,
                    payload = SetLikeStateCodec.encode(SetLikeState(42), LikeBody(false)),
                    enqueuedAtMillis = 2L,
                )
            val stale =
                MutationRecord(
                    id = "stale",
                    key = key.value,
                    codecId = SetLikeStateCodec.id,
                    payload = SetLikeStateCodec.encode(SetLikeState(42), LikeBody(true)),
                    enqueuedAtMillis = 1L,
                )
            // A durable store is allowed to return records in any order.
            store.save(newest)
            store.save(stale)
            val apiClient = RecordingApiClient()
            val queue =
                MutationQueue(
                    apiClient = apiClient,
                    scope = backgroundScope,
                    store = store,
                    codecs = listOf(SetLikeStateCodec),
                )

            queue.restore()
            settle()

            assertEquals(1, apiClient.calls.size)
            assertEquals(LikeBody(false), apiClient.calls.single().body)
            assertTrue(store.loadAll().isEmpty())
        }
}
