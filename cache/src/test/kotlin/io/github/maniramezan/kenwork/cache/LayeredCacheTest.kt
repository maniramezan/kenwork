package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LayeredCacheTest {
    @Test
    fun `a suspended disk promotion cannot overwrite a newer write`() =
        runTest {
            val key = CacheKey("k")
            val memory = InMemoryCache<String>()
            val disk = InMemoryCache<String>()
            disk.setValue("old", key)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val slowDisk =
                object : Cache<String> by disk {
                    override suspend fun entry(key: CacheKey): CacheEntry<String>? {
                        val snapshot = disk.entry(key)
                        entered.complete(Unit)
                        resume.await()
                        return snapshot
                    }
                }
            val layered = LayeredCache(memory, slowDisk)
            val read = async { layered.entry(key) }
            entered.await()
            val write = async(start = CoroutineStart.UNDISPATCHED) { layered.setValue("new", key) }
            assertFalse(write.isCompleted)
            resume.complete(Unit)
            read.await()
            write.await()
            assertEquals("new", memory.value(key))
            assertEquals("new", disk.value(key))
        }

    @Test
    fun `a suspended disk promotion cannot resurrect removed entries`() =
        runTest {
            for (clearAll in listOf(false, true)) {
                val key = CacheKey("k")
                val memory = InMemoryCache<String>()
                val disk = InMemoryCache<String>()
                disk.setValue("old", key)
                val entered = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val slowDisk =
                    object : Cache<String> by disk {
                        override suspend fun entry(key: CacheKey): CacheEntry<String>? {
                            val snapshot = disk.entry(key)
                            entered.complete(Unit)
                            resume.await()
                            return snapshot
                        }
                    }
                val layered = LayeredCache(memory, slowDisk)
                val read = async { layered.entry(key) }
                entered.await()
                val remove =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        if (clearAll) layered.removeAll() else layered.removeValue(key)
                    }
                assertFalse(remove.isCompleted)
                resume.complete(Unit)
                read.await()
                remove.await()
                assertNull(memory.value(key))
                assertNull(disk.value(key))
            }
        }

    @Test
    fun `reads from memory first`() =
        runTest {
            val memory = InMemoryCache<String>()
            val persistent = InMemoryCache<String>()
            memory.setValue("mem", CacheKey("k"))
            persistent.setValue("disk", CacheKey("k"))
            val layered = LayeredCache(memory, persistent)
            assertEquals("mem", layered.value(CacheKey("k")))
        }

    @Test
    fun `promotes a persistent hit into memory preserving its timestamp`() =
        runTest {
            val memory = InMemoryCache<String>(currentTimeMillis = { 9_999L })
            val persistent = InMemoryCache<String>(currentTimeMillis = { 100L })
            persistent.setValue("disk", CacheKey("k"))
            val layered = LayeredCache(memory, persistent)

            assertEquals("disk", layered.value(CacheKey("k")))
            // Promoted into memory with the persistent layer's original timestamp, not "now".
            assertEquals("disk", memory.value(CacheKey("k")))
            assertEquals(100L, memory.timestamp(CacheKey("k")))
        }

    @Test
    fun `writes through to both layers`() =
        runTest {
            val memory = InMemoryCache<String>()
            val persistent = InMemoryCache<String>()
            val layered = LayeredCache(memory, persistent)
            layered.setValue("v", CacheKey("k"))
            assertEquals("v", memory.value(CacheKey("k")))
            assertEquals("v", persistent.value(CacheKey("k")))
        }

    @Test
    fun `memory-only layered cache returns null on miss`() =
        runTest {
            val layered = LayeredCache(InMemoryCache<String>())
            assertNull(layered.value(CacheKey("absent")))
        }

    @Test
    fun `removeAll clears both layers`() =
        runTest {
            val memory = InMemoryCache<String>()
            val persistent = InMemoryCache<String>()
            val layered = LayeredCache(memory, persistent)
            layered.setValue("v", CacheKey("k"))
            layered.removeAll()
            assertNull(memory.value(CacheKey("k")))
            assertNull(persistent.value(CacheKey("k")))
        }

    @Test
    fun `a failed persistent write leaves memory untouched instead of diverging`() =
        runTest {
            val memory = InMemoryCache<String>()
            val persistent = FailingCache<String>()
            val layered = LayeredCache(memory, persistent)

            assertFailsWith<IllegalStateException> { layered.setValue("v", CacheKey("k")) }

            // The durable write is attempted first, so a failure there must not leave memory
            // holding a value the disk layer never actually persisted.
            assertNull(memory.value(CacheKey("k")))
        }

    @Test
    fun `a failed persistent removal leaves memory intact`() =
        runTest {
            val key = CacheKey("k")
            val memory = InMemoryCache<String>().also { it.setValue("v", key) }
            val layered = LayeredCache(memory, FailingCache(failRemovals = true))

            assertFailsWith<IllegalStateException> { layered.removeValue(key) }

            assertEquals("v", memory.value(key))
        }

    @Test
    fun `a failed persistent clear leaves memory intact`() =
        runTest {
            val key = CacheKey("k")
            val memory = InMemoryCache<String>().also { it.setValue("v", key) }
            val layered = LayeredCache(memory, FailingCache(failClears = true))

            assertFailsWith<IllegalStateException> { layered.removeAll() }

            assertEquals("v", memory.value(key))
        }
}

/** A [Cache] whose [setValue] always fails, for exercising write-ordering/failure semantics. */
private class FailingCache<V : Any>(
    private val failWrites: Boolean = true,
    private val failRemovals: Boolean = false,
    private val failClears: Boolean = false,
) : Cache<V> {
    override suspend fun value(key: CacheKey): V? = null

    override suspend fun setValue(
        value: V,
        key: CacheKey,
    ): Unit = if (failWrites) error("write failed") else Unit

    override suspend fun removeValue(key: CacheKey): Unit = if (failRemovals) error("removal failed") else Unit

    override suspend fun removeAll(): Unit = if (failClears) error("clear failed") else Unit

    override suspend fun timestamp(key: CacheKey): Long? = null
}
