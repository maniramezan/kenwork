package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheEntry
import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.InMemoryCache
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private data class DefaultsItem(
    val value: String,
)

private class DefaultsEndpoint : NetworkEndpoint {
    override val baseUrl = "https://api.test"
    override val path = "items/1"
    override val method = HttpMethod.GET
}

/** A [LocalDataSource] that overrides only the primitives, exercising the default entry/changes. */
private class MapLocalDataSource : LocalDataSource<DefaultsItem> {
    private val values = mutableMapOf<CacheKey, DefaultsItem>()
    private val timestamps = mutableMapOf<CacheKey, Long>()

    override suspend fun read(key: CacheKey): DefaultsItem? = values[key]

    override suspend fun write(
        entity: DefaultsItem,
        key: CacheKey,
    ) {
        values[key] = entity
        timestamps[key] = 100L
    }

    override suspend fun remove(key: CacheKey) {
        values.remove(key)
        timestamps.remove(key)
    }

    override suspend fun removeAll() {
        values.clear()
        timestamps.clear()
    }

    override suspend fun timestamp(key: CacheKey): Long? = timestamps[key]
}

class RepositoryDefaultsTest {
    private val key = CacheKey("items/1")
    private val endpoint = DefaultsEndpoint()
    private val network =
        object : NetworkDataSource {
            @Suppress("UNCHECKED_CAST")
            override suspend fun <T> request(
                endpoint: NetworkEndpoint,
                body: Any?,
                bodyType: TypeInfo?,
                responseType: TypeInfo,
            ): T = DefaultsItem("net") as T
        }

    @Test
    fun `default LocalDataSource entry composes read and timestamp`() =
        runTest {
            val local = MapLocalDataSource()
            assertNull(local.entry(key))
            local.write(DefaultsItem("v"), key)
            assertEquals(CacheEntry(DefaultsItem("v"), 100L), local.entry(key))
        }

    @Test
    fun `default LocalDataSource is usable by the repository and is non-reactive`() =
        runTest {
            val repository = GenericRepository<DefaultsItem>(network, MapLocalDataSource(), scope = backgroundScope)
            // ReturnCacheIfNotExpired drives the default entry() path on a non-reactive source.
            val result = repository.fetch(endpoint, key, CachePolicy.ReturnCacheIfNotExpired(maxAgeMillis = 1_000L))
            assertEquals(DefaultsItem("net"), result)
            // A non-reactive source's stream still emits the initial fetch and then completes
            // against an empty changes() flow.
            assertEquals(DefaultsItem("net"), repository.stream(endpoint, key, CachePolicy.ReturnCacheElseLoad).first())
        }

    @Test
    fun `default Repository stream emits a single fetch`() =
        runTest {
            val repository =
                object : Repository<DefaultsItem> {
                    override suspend fun fetch(
                        endpoint: NetworkEndpoint,
                        cacheKey: CacheKey,
                        policy: CachePolicy,
                    ): DefaultsItem = DefaultsItem("once")
                }
            assertEquals(listOf(DefaultsItem("once")), repository.stream(endpoint, key).toList())
        }

    @Test
    fun `close cancels the internally-owned scope`() =
        runTest {
            // No scope supplied: close() owns and cancels its scope.
            val repository = GenericRepository<DefaultsItem>(network, CacheBasedLocalDataSource(InMemoryCache()))
            repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache)
            repository.close()
        }

    @Test
    fun `close is a no-op for a supplied scope`() =
        runTest {
            val repository = GenericRepository<DefaultsItem>(network, MapLocalDataSource(), scope = backgroundScope)
            repository.close()
            // The supplied scope is still active, so the repository keeps working.
            assertEquals(DefaultsItem("net"), repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache))
        }

    @Test
    fun `CacheBasedLocalDataSource forwards remove, removeAll and timestamp`() =
        runTest {
            val cache = InMemoryCache<DefaultsItem>(currentTimeMillis = { 7L })
            val local = CacheBasedLocalDataSource(cache)
            local.write(DefaultsItem("v"), key)
            assertEquals(7L, local.timestamp(key))
            local.remove(key)
            assertNull(local.read(key))
            local.write(DefaultsItem("v2"), key)
            local.removeAll()
            assertNull(local.read(key))
        }
}
