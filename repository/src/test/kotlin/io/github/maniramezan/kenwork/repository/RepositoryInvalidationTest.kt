package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.InMemoryCache
import io.github.maniramezan.kenwork.cache.LayeredCache
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryInvalidationTest {
    private val endpoint =
        object : NetworkEndpoint {
            override val baseUrl = "https://api.test"
            override val path = "watched"
            override val method = HttpMethod.GET
        }
    private val network =
        object : NetworkDataSource {
            override suspend fun <T> request(
                endpoint: NetworkEndpoint,
                body: Any?,
                bodyType: TypeInfo?,
                responseType: TypeInfo,
            ): T = error("cache hit expected")
        }

    @Test
    fun `layered memory eviction clears existing observers`() =
        runTest {
            val key = CacheKey("watched")
            val cache = LayeredCache(InMemoryCache<String>(maxSize = 1))
            cache.setValue("old", key)
            val repository = GenericRepository<String>(network, CacheBasedLocalDataSource(cache), scope = backgroundScope)
            val observed = mutableListOf<String?>()
            backgroundScope.launch { repository.streamOrNull(endpoint, key, CachePolicy.ReturnCacheElseLoad).collect { observed += it } }
            runCurrent()
            cache.setValue("other", CacheKey("other"))
            runCurrent()
            assertEquals(null, cache.value(key))
            assertEquals(listOf("old", null), observed)
        }

    @Test
    fun `unrelated write burst cannot hide the latest watched value`() =
        runTest {
            val key = CacheKey("watched")
            val cache = InMemoryCache<String>()
            cache.setValue("old", key)
            val repository = GenericRepository<String>(network, CacheBasedLocalDataSource(cache), scope = backgroundScope)
            val observed = mutableListOf<String>()
            backgroundScope.launch { repository.stream(endpoint, key, CachePolicy.ReturnCacheElseLoad).collect { observed += it } }
            runCurrent()
            cache.setValue("fresh", key)
            repeat(100) { cache.setValue("unrelated", CacheKey("other-$it")) }
            runCurrent()
            assertEquals("fresh", cache.value(key))
            assertEquals(listOf("old", "fresh"), observed)
        }

    @Test
    fun `unrelated write burst cannot hide a clear`() =
        runTest {
            val key = CacheKey("watched")
            val cache = InMemoryCache<String>()
            cache.setValue("old", key)
            val repository = GenericRepository<String>(network, CacheBasedLocalDataSource(cache), scope = backgroundScope)
            val observed = mutableListOf<String?>()
            backgroundScope.launch { repository.streamOrNull(endpoint, key, CachePolicy.ReturnCacheElseLoad).collect { observed += it } }
            runCurrent()

            cache.removeAll()
            repeat(100) { cache.setValue("unrelated", CacheKey("other-$it")) }
            runCurrent()

            assertEquals(listOf("old", null), observed)
        }

    @Test
    fun `a write burst does not cause a local read for every notification`() =
        runTest {
            val key = CacheKey("watched")
            val cache = InMemoryCache<String>()
            cache.setValue("old", key)
            var reads = 0
            val local =
                object : LocalDataSource<String> by CacheBasedLocalDataSource(cache) {
                    override suspend fun read(key: CacheKey): String? {
                        reads++
                        return cache.value(key)
                    }
                }
            val repository = GenericRepository<String>(network, local, scope = backgroundScope)
            val observed = mutableListOf<String>()
            backgroundScope.launch { repository.stream(endpoint, key, CachePolicy.ReturnCacheElseLoad).collect { observed += it } }
            runCurrent()

            repeat(100) { cache.setValue("value-$it", key) }
            runCurrent()

            assertEquals(listOf("old", "value-99"), observed)
            assertTrue(reads <= 3, "A burst should require only the initial read and conflated invalidations; got $reads reads")
        }
}
