package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.InMemoryCache
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private data class ReactiveItem(
    val value: String,
)

private class ReactiveEndpoint : NetworkEndpoint {
    override val baseUrl = "https://api.test"
    override val path = "items/1"
    override val method = HttpMethod.GET
}

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryReactiveTest {
    private val key = CacheKey("items/1")
    private val endpoint = ReactiveEndpoint()

    @Test
    fun `coalesces concurrent loads for the same key into one network call`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val network =
                object : NetworkDataSource {
                    @Suppress("UNCHECKED_CAST")
                    override suspend fun <T> request(
                        endpoint: NetworkEndpoint,
                        body: Any?,
                        bodyType: TypeInfo?,
                        responseType: TypeInfo,
                    ): T {
                        calls++
                        gate.await()
                        return ReactiveItem("net") as T
                    }
                }
            val local = CacheBasedLocalDataSource(InMemoryCache<ReactiveItem>())
            val repository = GenericRepository<ReactiveItem>(network, local, scope = backgroundScope)

            val a = async { repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache) }
            val b = async { repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache) }
            runCurrent()
            gate.complete(Unit)

            assertEquals(ReactiveItem("net"), a.await())
            assertEquals(ReactiveItem("net"), b.await())
            assertEquals(1, calls)
        }

    @Test
    fun `loads again after an in-flight burst completes`() =
        runTest {
            var calls = 0
            val network =
                object : NetworkDataSource {
                    @Suppress("UNCHECKED_CAST")
                    override suspend fun <T> request(
                        endpoint: NetworkEndpoint,
                        body: Any?,
                        bodyType: TypeInfo?,
                        responseType: TypeInfo,
                    ): T {
                        calls++
                        return ReactiveItem("net") as T
                    }
                }
            val local = CacheBasedLocalDataSource(InMemoryCache<ReactiveItem>())
            val repository = GenericRepository<ReactiveItem>(network, local, scope = backgroundScope)

            repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache)
            repository.fetch(endpoint, key, CachePolicy.ReloadIgnoringCache)

            assertEquals(2, calls)
        }

    @Test
    fun `stream emits the initial value then re-emits on a local change`() =
        runTest {
            val network =
                object : NetworkDataSource {
                    @Suppress("UNCHECKED_CAST")
                    override suspend fun <T> request(
                        endpoint: NetworkEndpoint,
                        body: Any?,
                        bodyType: TypeInfo?,
                        responseType: TypeInfo,
                    ): T = ReactiveItem("net") as T
                }
            val local = CacheBasedLocalDataSource(InMemoryCache<ReactiveItem>())
            val repository = GenericRepository<ReactiveItem>(network, local, scope = backgroundScope)

            val emissions = mutableListOf<ReactiveItem>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.stream(endpoint, key, CachePolicy.ReturnCacheElseLoad).collect { emissions += it }
            }
            runCurrent()

            local.write(ReactiveItem("updated"), key)
            runCurrent()

            assertEquals(listOf(ReactiveItem("net"), ReactiveItem("updated")), emissions)
        }

    @Test
    fun `stream does not miss a change that lands during the initial fetch`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val network =
                object : NetworkDataSource {
                    @Suppress("UNCHECKED_CAST")
                    override suspend fun <T> request(
                        endpoint: NetworkEndpoint,
                        body: Any?,
                        bodyType: TypeInfo?,
                        responseType: TypeInfo,
                    ): T {
                        gate.await()
                        return ReactiveItem("net") as T
                    }
                }
            val local = CacheBasedLocalDataSource(InMemoryCache<ReactiveItem>())
            val repository = GenericRepository<ReactiveItem>(network, local, scope = backgroundScope)

            val emissions = mutableListOf<ReactiveItem>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.stream(endpoint, key, CachePolicy.ReloadIgnoringCache).collect { emissions += it }
            }
            runCurrent()

            // A change lands while the initial fetch's network call is still in flight. With
            // `changes()` subscribed before the fetch runs, this must not be dropped even though
            // the collector hasn't observed the fetch's own result yet.
            local.write(ReactiveItem("racing"), key)
            runCurrent()
            assertEquals(listOf(ReactiveItem("racing")), emissions)

            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(ReactiveItem("racing"), ReactiveItem("net")), emissions)
        }
}
