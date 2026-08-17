package io.github.maniramezan.kenwork.mutations

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryMutationStoreTest {
    private fun record(id: String) =
        MutationRecord(
            id = id,
            key = "like:video:$id",
            codecId = "set-like-state",
            payload = """{"videoId":$id,"liked":true}""",
            enqueuedAtMillis = 1_000L,
        )

    @Test
    fun `save then loadAll round-trips a record`() =
        runTest {
            val store = InMemoryMutationStore()
            store.save(record("1"))

            val loaded = store.loadAll()

            assertEquals(1, loaded.size)
            assertEquals(record("1"), loaded.single())
        }

    @Test
    fun `saving the same id twice overwrites, not duplicates`() =
        runTest {
            val store = InMemoryMutationStore()
            store.save(record("1"))
            store.save(record("1").copy(payload = """{"videoId":1,"liked":false}"""))

            val loaded = store.loadAll()

            assertEquals(1, loaded.size)
            assertEquals("""{"videoId":1,"liked":false}""", loaded.single().payload)
        }

    @Test
    fun `remove deletes only the matching id`() =
        runTest {
            val store = InMemoryMutationStore()
            store.save(record("1"))
            store.save(record("2"))

            store.remove("1")

            val loaded = store.loadAll()
            assertEquals(1, loaded.size)
            assertEquals("2", loaded.single().id)
        }

    @Test
    fun `loadAll is empty for a fresh store`() =
        runTest {
            assertTrue(InMemoryMutationStore().loadAll().isEmpty())
        }
}
