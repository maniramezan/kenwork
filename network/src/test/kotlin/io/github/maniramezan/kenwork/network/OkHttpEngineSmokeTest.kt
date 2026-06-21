package io.github.maniramezan.kenwork.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end smoke test against the **real** OkHttp engine (live sockets + TLS), validating the
 * `buildClient` OkHttp path that [MockEngine]-based tests bypass — most importantly that the pinned
 * OkHttp version is runtime-compatible with the Ktor engine.
 *
 * Opt-in: it performs a real network request and is skipped unless `KENWORK_SMOKE_TEST=1`, so CI
 * and offline runs stay hermetic. Run it explicitly with, e.g.:
 *
 * ```
 * KENWORK_SMOKE_TEST=1 ./gradlew :network:testDebugUnitTest --tests '*OkHttpEngineSmokeTest*'
 * ```
 */
class OkHttpEngineSmokeTest {
    @Serializable
    private data class Todo(
        val id: Int,
        val completed: Boolean,
    )

    private class TodoEndpoint : NetworkEndpoint {
        override val baseUrl = "https://jsonplaceholder.typicode.com"
        override val path = "todos/1"
        override val method = HttpMethod.GET
    }

    @Test
    fun `real OkHttp engine performs a GET and decodes JSON`(): Unit =
        runBlocking {
            assumeTrue(
                "Set KENWORK_SMOKE_TEST=1 to run the live OkHttp smoke test",
                System.getenv("KENWORK_SMOKE_TEST") == "1",
            )
            val client = NetworkClient()
            val todo: Todo = client.request(TodoEndpoint())
            assertEquals(1, todo.id)
        }
}
