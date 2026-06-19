package io.github.maniramezan.kemwork.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryCacheTest {
    @Test
    fun `stores and retrieves a value`() =
        runTest {
            val cache = InMemoryCache<String>()
            cache.setValue("v", CacheKey("k"))
            assertEquals("v", cache.value(CacheKey("k")))
        }

    @Test
    fun `evicts the least recently used entry past maxSize`() =
        runTest {
            val cache = InMemoryCache<Int>(maxSize = 2)
            cache.setValue(1, CacheKey("a"))
            cache.setValue(2, CacheKey("b"))
            // Touch "a" so "b" becomes least-recently-used.
            cache.value(CacheKey("a"))
            cache.setValue(3, CacheKey("c"))

            assertNull(cache.value(CacheKey("b")))
            assertEquals(1, cache.value(CacheKey("a")))
            assertEquals(3, cache.value(CacheKey("c")))
            assertEquals(2, cache.count())
        }

    @Test
    fun `removes entries older than the max age`() =
        runTest {
            var now = 1_000L
            val cache = InMemoryCache<String>(currentTimeMillis = { now })
            cache.setValue("fresh", CacheKey("k"))
            now = 5_000L
            cache.removeExpiredEntries(maxAgeMillis = 1_000L)
            assertNull(cache.value(CacheKey("k")))
        }

    @Test
    fun `keeps entries within the max age`() =
        runTest {
            var now = 1_000L
            val cache = InMemoryCache<String>(currentTimeMillis = { now })
            cache.setValue("fresh", CacheKey("k"))
            now = 1_500L
            cache.removeExpiredEntries(maxAgeMillis = 1_000L)
            assertEquals("fresh", cache.value(CacheKey("k")))
        }

    @Test
    fun `records the injected timestamp`() =
        runTest {
            val cache = InMemoryCache<String>(currentTimeMillis = { 42L })
            cache.setValue("v", CacheKey("k"))
            assertEquals(42L, cache.timestamp(CacheKey("k")))
        }

    @Test
    fun `removeAll clears the cache`() =
        runTest {
            val cache = InMemoryCache<String>()
            cache.setValue("v", CacheKey("k"))
            cache.removeAll()
            assertEquals(0, cache.count())
            assertNull(cache.value(CacheKey("k")))
        }
}
