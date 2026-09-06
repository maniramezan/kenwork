package io.github.maniramezan.kenwork.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KenworkHttpClientTest {
    @Test
    fun bothOverloadsDecodeJsonWithUnknownFields() =
        runTest {
            for (useFactory in listOf(false, true)) {
                withClient(useFactory, handler = {
                    respond("""{"name":"Ada","futureField":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }) { client ->
                    assertEquals(Person("Ada"), client.get("https://api.test/person").body<Person>())
                }
            }
        }

    @Test
    fun bothOverloadsHonorTheSuppliedJsonPolicy() =
        runTest {
            for (useFactory in listOf(false, true)) {
                withClient(useFactory, json = Json { ignoreUnknownKeys = false }, handler = {
                    respond("""{"name":"Ada","futureField":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }) { client ->
                    assertFailsWith<JsonConvertException> { client.get("https://api.test/person").body<Person>() }
                }
            }
        }

    @Test
    fun redirectsRequireExplicitOptInForBothOverloads() =
        runTest {
            for (useFactory in listOf(false, true)) {
                for (follow in listOf(false, true)) {
                    var calls = 0
                    withClient(useFactory, followRedirects = follow, handler = {
                        calls++
                        if (it.url.encodedPath == "/start") {
                            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://api.test/end"))
                        } else {
                            respond("done")
                        }
                    }) { client ->
                        assertEquals(if (follow) HttpStatusCode.OK else HttpStatusCode.Found, client.get("https://api.test/start").status)
                        assertEquals(if (follow) 2 else 1, calls)
                    }
                }
            }
        }

    private suspend fun withClient(
        useFactory: Boolean,
        json: Json? = null,
        followRedirects: Boolean = false,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        block: suspend (HttpClient) -> Unit,
    ) {
        val engine = MockEngine(handler)
        val factory =
            object : HttpClientEngineFactory<HttpClientEngineConfig> {
                override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine = engine
            }
        val client =
            if (useFactory) {
                if (json == null && !followRedirects) {
                    KenworkHttpClient.create(factory)
                } else if (json == null) {
                    KenworkHttpClient.create(factory, followRedirects = followRedirects)
                } else {
                    KenworkHttpClient.create(factory, json, followRedirects)
                }
            } else {
                if (json == null && !followRedirects) {
                    KenworkHttpClient.create(engine)
                } else if (json == null) {
                    KenworkHttpClient.create(engine, followRedirects = followRedirects)
                } else {
                    KenworkHttpClient.create(engine, json, followRedirects)
                }
            }
        try {
            block(client)
        } finally {
            client.close()
            engine.close()
        }
    }
}

@Serializable
private data class Person(
    val name: String,
)
