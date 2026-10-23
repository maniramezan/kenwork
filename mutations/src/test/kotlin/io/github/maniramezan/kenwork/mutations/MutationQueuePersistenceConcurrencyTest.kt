package io.github.maniramezan.kenwork.mutations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MutationQueuePersistenceConcurrencyTest {
    @Test
    fun `cancel waits for an enqueue being saved before removing its work`() =
        runTest {
            val saving = CompletableDeferred<Unit>()
            val allowSave = CompletableDeferred<Unit>()
            val backing = InMemoryMutationStore()
            val store =
                object : MutationStore by backing {
                    override suspend fun save(record: MutationRecord) {
                        saving.complete(Unit)
                        allowSave.await()
                        backing.save(record)
                    }
                }
            val queue = MutationQueue(RecordingApiClient { _, _ -> awaitCancellation() }, backgroundScope, store = store)
            val key = MutationKey("saving")
            val enqueue = async { queue.enqueue(key, SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec) }
            saving.await()

            val cancel = async { queue.cancel(key) }
            runCurrent()
            assertFalse(cancel.isCompleted)

            allowSave.complete(Unit)
            enqueue.await()
            cancel.await()
            settle()

            assertNull(queue.statusFlow(key).value)
            assertTrue(backing.loadAll().isEmpty())
        }

    @Test
    fun `cancel waits for restoration so a loaded snapshot cannot revive cancelled work`() =
        runTest {
            val loading = CompletableDeferred<Unit>()
            val allowLoad = CompletableDeferred<Unit>()
            val backing = InMemoryMutationStore()
            val key = MutationKey("restoring")
            backing.save(
                MutationRecord("saved", key.value, SetLikeStateCodec.id, SetLikeStateCodec.encode(SetLikeState(42), LikeBody(true)), 0L),
            )
            val store =
                object : MutationStore by backing {
                    override suspend fun loadAll(): List<MutationRecord> {
                        val snapshot = backing.loadAll()
                        loading.complete(Unit)
                        allowLoad.await()
                        return snapshot
                    }
                }
            val queue =
                MutationQueue(
                    RecordingApiClient { _, _ -> awaitCancellation() },
                    backgroundScope,
                    store = store,
                    codecs = listOf(SetLikeStateCodec),
                )
            val restore = async { queue.restore() }
            loading.await()

            val cancel = async { queue.cancel(key) }
            runCurrent()
            assertFalse(cancel.isCompleted)

            allowLoad.complete(Unit)
            restore.await()
            cancel.await()
            settle()

            assertNull(queue.statusFlow(key).value)
            assertTrue(backing.loadAll().isEmpty())
        }
}
