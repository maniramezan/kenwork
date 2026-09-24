package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.testing.FakeApiClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class VideoRepositorySampleTest {
    private val cacheDir: File = createTempDirectory("videos").toFile()
    private val api = FakeApiClient { request -> Video(id = 1, title = "from network #${request.index}") }

    @AfterTest
    fun cleanup() {
        cacheDir.deleteRecursively()
    }

    private fun TestScope.repository(now: () -> Long = { 0L }): VideoRepository =
        VideoRepository(
            network = api,
            cacheDirectory = cacheDir,
            ioContext = UnconfinedTestDispatcher(testScheduler),
            scope = backgroundScope,
            currentTimeMillis = now,
        )

    @Test
    fun `serves fresh data from cache and reloads once it is stale`() =
        runTest {
            var now = 0L
            val videos = repository { now }

            assertEquals("from network #0", videos.video(1).title)
            assertEquals("from network #0", videos.video(1).title) // cache hit

            now += VideoRepository.DEFAULT_MAX_AGE_MILLIS + 1
            assertEquals("from network #1", videos.video(1).title) // expired → reload
            assertEquals(2, api.requests.size)
        }

    @Test
    fun `a new repository instance reads what the last one persisted to disk`() =
        runTest {
            repository().video(1)

            // Simulates a process restart: fresh memory tier, same cache directory.
            val afterRestart = repository()
            assertEquals("from network #0", afterRestart.video(1).title)
            assertEquals(1, api.requests.size)
        }

    @Test
    fun `observe follows optimistic writes and clears`() =
        runTest {
            val videos = repository()
            val seen = mutableListOf<Video?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { videos.observe(1).collect { seen += it } }
            runCurrent()

            videos.store(Video(id = 1, title = "from network #0", liked = true))
            videos.clear()
            runCurrent()

            assertEquals(
                listOf(Video(1, "from network #0"), Video(1, "from network #0", liked = true), null),
                seen,
            )
        }
}
