package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LayeredCacheTest {
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
}

/** A [Cache] whose [setValue] always fails, for exercising write-ordering/failure semantics. */
private class FailingCache<V : Any> : Cache<V> {
    override suspend fun value(key: CacheKey): V? = null

    override suspend fun setValue(
        value: V,
        key: CacheKey,
    ): Unit = error("write failed")

    override suspend fun removeValue(key: CacheKey): Unit = Unit

    override suspend fun removeAll(): Unit = Unit

    override suspend fun timestamp(key: CacheKey): Long? = null
}
