package io.github.maniramezan.kenwork.mutations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileMutationStoreTest {
    private val dir: File = createTempDirectory("mutations").toFile()
    private val file = File(dir, "pending/mutations.json")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun TestScope.store(): FileMutationStore = FileMutationStore(file, ioContext = StandardTestDispatcher(testScheduler))

    private fun record(
        id: String,
        key: String = "k$id",
    ) = MutationRecord(id, key, "codec", """{"id":"$id"}""", id.toLong())

    @Test
    fun `records survive a new store instance on the same file`() =
        runTest {
            val first = store()
            first.save(record("1"))
            first.save(record("2"))
            first.remove("1")

            assertEquals(listOf(record("2")), store().loadAll())
        }

    @Test
    fun `saving an existing id replaces it`() =
        runTest {
            val store = store()
            store.save(record("1"))
            store.save(record("1").copy(payload = "updated"))
            assertEquals(listOf("updated"), store().loadAll().map { it.payload })
        }

    @Test
    fun `removeByKey removes every record for that key`() =
        runTest {
            val store = store()
            store.save(record("1", key = "a"))
            store.save(record("2", key = "a"))
            store.save(record("3", key = "b"))
            store.removeByKey("a")
            assertEquals(listOf("3"), store().loadAll().map { it.id })
        }

    @Test
    fun `clear deletes the file`() =
        runTest {
            val store = store()
            store.save(record("1"))
            store.clear()
            assertFalse(file.exists())
            assertTrue(store.loadAll().isEmpty())
        }

    @Test
    fun `an unreadable file loads as empty and is replaced by the next write`() =
        runTest {
            requireNotNull(file.parentFile).mkdirs()
            file.writeText("not json")
            val store = store()
            assertTrue(store.loadAll().isEmpty())
            store.save(record("1"))
            assertEquals(listOf(record("1")), store().loadAll())
        }

    @Test
    fun `a queue restores persisted mutations from the file after a restart`() =
        runTest {
            // The first "process" persists the mutation, then dies before the request completes.
            val dying = CoroutineScope(backgroundScope.coroutineContext + Job())
            val hanging = RecordingApiClient { _, _ -> awaitCancellation() }
            MutationQueue(hanging, dying, store(), codecs = listOf(SetLikeStateCodec))
                .enqueue(MutationKey("like:42"), SetLikeState(42), LikeBody(true), codec = SetLikeStateCodec)
            settle()
            dying.cancel()
            assertEquals(1, store().loadAll().size)

            val replayed = RecordingApiClient()
            val restartedStore = store()
            MutationQueue(replayed, backgroundScope, restartedStore, codecs = listOf(SetLikeStateCodec)).restore()
            settle()

            assertEquals(listOf<Any?>(LikeBody(true)), replayed.calls.map { it.body })
            assertTrue(store().loadAll().isEmpty())
        }
}
