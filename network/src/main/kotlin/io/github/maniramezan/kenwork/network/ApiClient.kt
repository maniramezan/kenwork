package io.github.maniramezan.kenwork.network

import io.ktor.util.reflect.TypeInfo
import io.ktor.util.reflect.typeInfo

/**
 * The minimal request contract. Mirrors SwiftyNetwork's `APIClient`.
 *
 * The single core method is type-erased (it takes Ktor [TypeInfo]) so it can live on an
 * interface; prefer the reified [request] extensions below for ergonomic call sites.
 */
public interface ApiClient {
    /**
     * Executes [endpoint], optionally sending [body] (described by [bodyType]), and decodes the
     * response into [responseType].
     */
    public suspend fun <T> request(
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
    ): T
}

/**
 * Marker for an [ApiClient] usable as a repository's remote data source. Mirrors SwiftyNetwork's
 * `NetworkDataSource`.
 */
public interface NetworkDataSource : ApiClient

/** Executes [endpoint] and decodes the response into [T]. */
public suspend inline fun <reified T> ApiClient.request(endpoint: NetworkEndpoint): T = request(endpoint, null, null, typeInfo<T>())

/** Executes [endpoint] sending [body] of type [B], and decodes the response into [T]. */
public suspend inline fun <reified B, reified T> ApiClient.request(
    endpoint: NetworkEndpoint,
    body: B,
): T = request(endpoint, body, typeInfo<B>(), typeInfo<T>())

/** Executes [endpoint], discarding any response body. */
public suspend fun ApiClient.execute(endpoint: NetworkEndpoint) {
    request<Unit>(endpoint, null, null, typeInfo<Unit>())
}
