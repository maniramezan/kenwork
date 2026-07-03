package io.github.maniramezan.kenwork.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachePolicyAndKeyTest {
    @Test
    fun `returnCacheElseLoad always allows cached data`() {
        assertTrue(CachePolicy.ReturnCacheElseLoad.shouldUseCachedData(Long.MAX_VALUE))
    }

    @Test
    fun `reloadIgnoringCache never allows cached data`() {
        assertFalse(CachePolicy.ReloadIgnoringCache.shouldUseCachedData(0))
    }

    @Test
    fun `returnCacheIfNotExpired honors the max age boundary`() {
        val policy = CachePolicy.ReturnCacheIfNotExpired(maxAgeMillis = 1_000L)
        assertTrue(policy.shouldUseCachedData(999L))
        assertTrue(policy.shouldUseCachedData(1_000L))
        assertFalse(policy.shouldUseCachedData(1_001L))
    }

    @Test
    fun `cache key factories build stable identities`() {
        assertEquals(CacheKey("user:42:profile"), CacheKey.user("42", "profile"))
        assertEquals(CacheKey("a:b:c"), CacheKey.components(listOf("a", "b", "c")))
        assertEquals(CacheKey("videos"), CacheKey.endpoint("videos"))
        // Parameters are sorted, so identity is independent of insertion order.
        assertEquals(
            CacheKey.endpoint("videos", mapOf("offset" to "0", "limit" to "20")),
            CacheKey.endpoint("videos", mapOf("limit" to "20", "offset" to "0")),
        )
    }

    @Test
    fun `endpoint keys percent-encode parameters so separators can't collide`() {
        // A literal `&`/`=` inside a value must not be mistaken for the key's own separators, so
        // these two distinct parameter sets never produce the same CacheKey.
        val embeddedSeparators = CacheKey.endpoint("search", mapOf("q" to "a&b=c"))
        val splitParameters = CacheKey.endpoint("search", mapOf("q" to "a", "b" to "c"))
        assertFalse(embeddedSeparators == splitParameters)
    }
}
