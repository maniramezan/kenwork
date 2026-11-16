package io.github.maniramezan.kenwork.mutations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutationQueueAdmissionTest {
    @Test
    fun `a slow store write for one key does not block enqueueing another key`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val delegate = InMemoryMutationStore()
            val store =
                object : MutationStore by delegate {
                    override suspend fun save(record: MutationRecord) {
                        if (record.key == "a") gate.await()
                        delegate.save(record)
                    }
                }
            val queue = MutationQueue(RecordingApiClient(), backgroundScope, store)

            val slow = async { queue.enqueue(MutationKey("a"), SetLikeState(1), LikeBody(true), codec = SetLikeStateCodec) }
            runCurrent()
            assertFalse(slow.isCompleted)

            val fast = async { queue.enqueue(MutationKey("b"), SetLikeState(2), LikeBody(true), codec = SetLikeStateCodec) }
            runCurrent()
            assertTrue(fast.isCompleted)

            gate.complete(Unit)
            slow.await()
            settle()
        }

    @Test
    fun `cancel removes a key's records without loading the whole store`() =
        runTest {
            var loadAllCalls = 0
            val delegate = InMemoryMutationStore()
            val store =
                object : MutationStore by delegate {
                    override suspend fun loadAll(): List<MutationRecord> {
                        loadAllCalls++
                        return delegate.loadAll()
                    }
                }
            delegate.save(MutationRecord("1", "a", SetLikeStateCodec.id, "{}", 1))
            delegate.save(MutationRecord("2", "b", SetLikeStateCodec.id, "{}", 2))
            val queue = MutationQueue(RecordingApiClient(), backgroundScope, store)

            queue.cancel(MutationKey("a"))

            assertEquals(0, loadAllCalls)
            assertEquals(listOf("2"), delegate.loadAll().map { it.id })
        }

    @Test
    fun `the default removeByKey works for stores that only implement the original three functions`() =
        runTest {
            val records = mutableMapOf<String, MutationRecord>()
            val store =
                object : MutationStore {
                    override suspend fun save(record: MutationRecord) {
                        records[record.id] = record
                    }

                    override suspend fun remove(id: String) {
                        records.remove(id)
                    }

                    override suspend fun loadAll(): List<MutationRecord> = records.values.toList()
                }
            store.save(MutationRecord("1", "a", "c", "{}", 1))
            store.save(MutationRecord("2", "a", "c", "{}", 2))
            store.save(MutationRecord("3", "b", "c", "{}", 3))

            store.removeByKey("a")

            assertEquals(setOf("3"), records.keys)
        }
}
