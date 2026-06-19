package io.github.maniramezan.kemwork.cache

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
}
