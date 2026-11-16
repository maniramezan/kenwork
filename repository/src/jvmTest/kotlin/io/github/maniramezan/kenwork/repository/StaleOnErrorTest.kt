package io.github.maniramezan.kenwork.repository

import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.InMemoryCache
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.github.maniramezan.kenwork.network.NetworkError
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private object StaleEndpoint : NetworkEndpoint {
    override val baseUrl = "https://api.test"
    override val path = "items/1"
    override val method = HttpMethod.GET
}

/** Returns [first] once, then fails every later request with [failure]. */
private class FailingAfterFirst(
    private val first: String,
    private val failure: Throwable,
) : NetworkDataSource {
    var calls = 0

    override suspend fun <T> request(
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
    ): T {
        calls++
        @Suppress("UNCHECKED_CAST")
        if (calls == 1) return first as T
        throw failure
    }
}

class StaleOnErrorTest {
    private val key = CacheKey("items/1")
    private val expired = CachePolicy.ReturnCacheIfNotExpired(maxAgeMillis = 10)

    private fun repository(
        network: NetworkDataSource,
        now: () -> Long,
        serveStaleOnError: Boolean,
    ): GenericRepository<String> =
        GenericRepository(
            networkDataSource = network,
            localDataSource = CacheBasedLocalDataSource(InMemoryCache(currentTimeMillis = now)),
            currentTimeMillis = now,
            serveStaleOnError = serveStaleOnError,
        )

    @Test
    fun `an expired entry is served when the reload fails transiently`() =
        runTest {
            var now = 0L
            val repo = repository(FailingAfterFirst("cached", NetworkError.NoInternetConnection), { now }, serveStaleOnError = true)
            repo.fetch(StaleEndpoint, key, expired)
            now = 1_000L
            assertEquals("cached", repo.fetch(StaleEndpoint, key, expired))
            assertEquals("cached", repo.fetch(StaleEndpoint, key, CachePolicy.ReloadIgnoringCache))
            repo.close()
        }

    @Test
    fun `a non-transient failure is still thrown`() =
        runTest {
            var now = 0L
            val repo = repository(FailingAfterFirst("cached", NetworkError.NotFound), { now }, serveStaleOnError = true)
            repo.fetch(StaleEndpoint, key, expired)
            now = 1_000L
            assertFailsWith<NetworkError.NotFound> { repo.fetch(StaleEndpoint, key, expired) }
            repo.close()
        }

    @Test
    fun `without the option a transient failure is thrown`() =
        runTest {
            var now = 0L
            val repo = repository(FailingAfterFirst("cached", NetworkError.Timeout), { now }, serveStaleOnError = false)
            repo.fetch(StaleEndpoint, key, expired)
            now = 1_000L
            assertFailsWith<NetworkError.Timeout> { repo.fetch(StaleEndpoint, key, expired) }
            repo.close()
        }

    @Test
    fun `with nothing cached the failure is thrown`() =
        runTest {
            val repo = repository(FailingAfterFirst("unused", NetworkError.Timeout).also { it.calls = 1 }, { 0L }, serveStaleOnError = true)
            assertFailsWith<NetworkError.Timeout> { repo.fetch(StaleEndpoint, key, expired) }
            repo.close()
        }
}
