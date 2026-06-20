package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
