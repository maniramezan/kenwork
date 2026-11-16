package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CacheObservabilityTest {
    private val key = CacheKey("k")

    @Test
    fun `entry returns value and timestamp atomically`() =
        runTest {
            val cache = InMemoryCache<String>(currentTimeMillis = { 42L })
            cache.setValue("v", key)
            assertEquals(CacheEntry("v", 42L), cache.entry(key))
        }

    @Test
    fun `entry is null when absent`() =
        runTest {
            assertNull(InMemoryCache<String>().entry(key))
        }

    @Test
    fun `inMemory emits Updated, Removed and Cleared`() =
        runTest {
            val cache = InMemoryCache<String>()
            val changes = collect(cache)

            cache.setValue("v", key)
            cache.removeValue(key)
            cache.setValue("v2", key)
            cache.removeAll()

            assertEquals(
                listOf(
                    CacheChange.Updated(key),
                    CacheChange.Removed(key),
                    CacheChange.Updated(key),
                    CacheChange.Cleared,
                ),
                changes,
            )
        }

    @Test
    fun `inMemory emits Removed for evicted keys`() =
        runTest {
            val cache = InMemoryCache<Int>(maxSize = 1)
            val changes = collect(cache)

            cache.setValue(1, CacheKey("a"))
            cache.setValue(2, CacheKey("b"))

            assertEquals(
                listOf(
                    CacheChange.Updated(CacheKey("a")),
                    CacheChange.Updated(CacheKey("b")),
                    CacheChange.Removed(CacheKey("a")),
                ),
                changes,
            )
        }

    @Test
    fun `layered promotes from persistent without emitting a change`() =
        runTest {
            val persistent = InMemoryCache<String>(currentTimeMillis = { 7L })
            persistent.setValue("p", key)
            val memory = InMemoryCache<String>(currentTimeMillis = { 99L })
            val layered = LayeredCache(memory, persistent)
            val changes = collect(layered)

            // Read promotes into memory preserving the persistent timestamp, but is not a "change".
            assertEquals(CacheEntry("p", 7L), layered.entry(key))
            assertEquals(7L, memory.timestamp(key))
            assertEquals(emptyList(), changes)
        }

    @Test
    fun `layered emits on writes and removals`() =
        runTest {
            val layered = LayeredCache(InMemoryCache<String>(), InMemoryCache<String>())
            val changes = collect(layered)

            layered.setValue("v", key)
            layered.removeValue(key)
            layered.removeAll()

            assertEquals(
                listOf(CacheChange.Updated(key), CacheChange.Removed(key), CacheChange.Cleared),
                changes,
            )
        }

    /** Subscribes to [cache] changes on an eager dispatcher and returns the backing list. */
    private fun TestScope.collect(cache: Cache<*>): List<CacheChange> {
        val changes = mutableListOf<CacheChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            cache.changes().collect { changes += it }
        }
        return changes
    }
}
