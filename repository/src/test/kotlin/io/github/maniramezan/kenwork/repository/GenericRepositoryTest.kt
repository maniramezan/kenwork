package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.InMemoryCache
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private data class Item(
    val value: String,
)

private class FakeEndpoint : NetworkEndpoint {
    override val baseUrl = "https://api.test"
    override val path = "items/1"
    override val method = HttpMethod.GET
}

private class FakeNetwork(
    private val produce: () -> Item,
) : NetworkDataSource {
    var calls = 0
        private set

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> request(
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
    ): T {
        calls++
        return produce() as T
    }
}

class GenericRepositoryTest {
    private val key = CacheKey("items/1")
    private val endpoint = FakeEndpoint()

    private fun repo(
        network: FakeNetwork,
        cache: InMemoryCache<Item> = InMemoryCache(),
        now: () -> Long = { 0L },
    ): Pair<GenericRepository<Item>, LocalDataSource<Item>> {
        val local = CacheBasedLocalDataSource(cache)
        return GenericRepository<Item>(network, local, currentTimeMillis = now) to local
    }

    @Test
    fun `returnCacheElseLoad returns cached value without hitting the network`() =
        runTest {
            val network = FakeNetwork { Item("net") }
            val (repository, local) = repo(network)
            local.write(Item("cached"), key)

            val result = repository.fetch(endpoint, key, CachePolicy.ReturnCacheElseLoad)

            assertEquals(Item("cached"), result)
            assertEquals(0, network.calls)
        }

    @Test
    fun `returnCacheElseLoad loads from network on a miss and writes through`() =
        runTest {
            val network = FakeNetwork { Item("net") }
            val (repository, local) = repo(network)

            val result = repository.fetch(endpoint, key, CachePolicy.ReturnCacheElseLoad)

            assertEquals(Item("net"), result)
            assertEquals(1, network.calls)
            assertEquals(Item("net"), local.read(key))
        }

    @Test
    fun `reloadIgnoringCache always loads and overwrites the cache`() =
        runTest {
            val network = FakeNetwork { Item("net") }
            val (repository, local) = repo(network)
            local.write(Item("cached"), key)

            val result = repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache)

            assertEquals(Item("net"), result)
            assertEquals(1, network.calls)
            assertEquals(Item("net"), local.read(key))
        }

    @Test
    fun `returnCacheIfNotExpired serves fresh cache but reloads stale cache`() =
        runTest {
            var now = 1_000L
            val cache = InMemoryCache<Item>(currentTimeMillis = { now })
            val network = FakeNetwork { Item("net") }
            val (repository, local) = repo(network, cache, now = { now })
            local.write(Item("cached"), key)

            // Fresh: within the max age.
            now = 1_500L
            assertEquals(
                Item("cached"),
                repository.fetch(endpoint, key, CachePolicy.ReturnCacheIfNotExpired(maxAgeMillis = 1_000L)),
            )
            assertEquals(0, network.calls)

            // Stale: beyond the max age -> reload.
            now = 3_000L
            assertEquals(
                Item("net"),
                repository.fetch(endpoint, key, CachePolicy.ReturnCacheIfNotExpired(maxAgeMillis = 1_000L)),
            )
            assertEquals(1, network.calls)
        }

    @Test
    fun `propagates network errors`() =
        runTest {
            val network = FakeNetwork { error("boom") }
            val (repository, _) = repo(network)
            assertFailsWith<IllegalStateException> {
                repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache)
            }
        }
}
