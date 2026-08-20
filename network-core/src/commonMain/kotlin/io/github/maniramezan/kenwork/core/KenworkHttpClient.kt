package io.github.maniramezan.kenwork.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Shared KMP client policy. Products provide the platform engine and own request semantics. */
public object KenworkHttpClient {
    public fun <T : HttpClientEngineConfig> create(
        engineFactory: HttpClientEngineFactory<T>,
        json: Json = Json { ignoreUnknownKeys = true },
        followRedirects: Boolean = false,
    ): HttpClient =
        HttpClient(engineFactory) {
            this.followRedirects = followRedirects
            install(ContentNegotiation) { json(json) }
        }

    public fun create(
        engine: HttpClientEngine,
        json: Json = Json { ignoreUnknownKeys = true },
        followRedirects: Boolean = false,
    ): HttpClient =
        HttpClient(engine) {
            this.followRedirects = followRedirects
            install(ContentNegotiation) { json(json) }
        }
}
