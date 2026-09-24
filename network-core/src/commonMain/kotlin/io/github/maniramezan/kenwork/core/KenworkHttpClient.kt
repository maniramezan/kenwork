package io.github.maniramezan.kenwork.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Shared KMP client policy. Products provide the platform engine and own request semantics.
 *
 * Every client built here gets the same policy: JSON content negotiation using the supplied [Json]
 * (default [DefaultJson]) and redirects disabled unless `followRedirects` is `true`. Nothing else
 * is installed — no auth, retry, timeout, or logging (see `docs/platforms.md`).
 */
public object KenworkHttpClient {
    /**
     * The default JSON policy: ignores unknown keys so additive server changes don't break
     * decoding. Shared rather than rebuilt per client, since each `Json` instance keeps its own
     * serializer cache.
     */
    public val DefaultJson: Json = Json { ignoreUnknownKeys = true }

    /**
     * Builds a client whose engine is created (and closed with the client) from [engineFactory],
     * e.g. `OkHttp` on Android/JVM or `Darwin` on iOS.
     *
     * @param json the JSON codec used for request and response bodies.
     * @param followRedirects whether Ktor follows `3xx` redirects; off by default.
     */
    public fun <T : HttpClientEngineConfig> create(
        engineFactory: HttpClientEngineFactory<T>,
        json: Json = DefaultJson,
        followRedirects: Boolean = false,
    ): HttpClient =
        HttpClient(engineFactory) {
            configurePolicy(json, followRedirects)
        }

    /**
     * Builds a client over an existing [engine]. The engine stays caller-owned: close it yourself
     * once every client using it has finished.
     *
     * @param json the JSON codec used for request and response bodies.
     * @param followRedirects whether Ktor follows `3xx` redirects; off by default.
     */
    public fun create(
        engine: HttpClientEngine,
        json: Json = DefaultJson,
        followRedirects: Boolean = false,
    ): HttpClient =
        HttpClient(engine) {
            configurePolicy(json, followRedirects)
        }

    private fun HttpClientConfig<*>.configurePolicy(
        json: Json,
        followRedirects: Boolean,
    ) {
        this.followRedirects = followRedirects
        install(ContentNegotiation) { json(json) }
    }
}
