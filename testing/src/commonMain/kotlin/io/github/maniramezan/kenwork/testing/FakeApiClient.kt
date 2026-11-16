package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo

/**
 * A scriptable [NetworkDataSource] (and therefore `ApiClient`) that never touches HTTP: every call
 * is recorded and answered by [handler]. Use it to unit-test code that sits *above* the client —
 * a `GenericRepository`, a `MutationQueue`, a ViewModel — without building a `MockEngine` or
 * encoding JSON.
 *
 * The value [handler] returns is handed back as the decoded response, so it must match the type
 * the caller requests (return `Unit` for `execute`). Throw a
 * [io.github.maniramezan.kenwork.network.NetworkError] to simulate a failure:
 *
 * ```kotlin
 * val api = FakeApiClient { request ->
 *     when (request.index) {
 *         0 -> throw NetworkError.Timeout
 *         else -> Video(id = 42, title = "Hi")
 *     }
 * }
 * val repository = GenericRepository<Video>(api, CacheBasedLocalDataSource(InMemoryCache()))
 * ```
 *
 * @param handler produces the response for each [RecordedRequest]; defaults to returning `Unit`.
 */
public class FakeApiClient(
    private val handler: suspend (RecordedRequest) -> Any? = { Unit },
) : NetworkDataSource {
    /**
     * One call made through this client.
     *
     * @property endpoint the endpoint requested.
     * @property body the typed request body, or `null` when none was sent.
     * @property responseType the type the caller asked the response to decode into.
     * @property index the 0-based order of this call across the client's lifetime.
     */
    public data class RecordedRequest(
        public val endpoint: NetworkEndpoint,
        public val body: Any?,
        public val responseType: TypeInfo,
        public val index: Int,
    )

    private val lock = Any()
    private val recorded = mutableListOf<RecordedRequest>()

    /** A snapshot of every request made so far, in call order. */
    public val requests: List<RecordedRequest>
        get() = synchronized(lock) { recorded.toList() }

    override suspend fun <T> request(
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
    ): T {
        val request =
            synchronized(lock) {
                RecordedRequest(endpoint, body, responseType, recorded.size).also { recorded += it }
            }
        @Suppress("UNCHECKED_CAST")
        return handler(request) as T
    }
}
