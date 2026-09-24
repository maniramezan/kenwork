package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.mutations.InMemoryMutationStore
import io.github.maniramezan.kenwork.mutations.MutationQueue
import io.github.maniramezan.kenwork.mutations.MutationStatus
import io.github.maniramezan.kenwork.testing.FakeApiClient
import io.ktor.util.reflect.typeInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LikeControllerSampleTest {
    private val cacheDir: File = createTempDirectory("likes").toFile()
    private val video = Video(id = 7, title = "Cats")

    @AfterTest
    fun cleanup() {
        cacheDir.deleteRecursively()
    }

    private fun TestScope.videos(api: FakeApiClient): VideoRepository =
        VideoRepository(api, cacheDir, ioContext = UnconfinedTestDispatcher(testScheduler), scope = backgroundScope)

    @Test
    fun `likes apply locally at once and rapid toggles send only the final state`() =
        runTest {
            val api = FakeApiClient()
            val videos = videos(api)
            val store = InMemoryMutationStore()
            val likes = LikeController(MutationQueue(api, backgroundScope, store, codecs = listOf(SetLikeStateCodec)), videos)

            likes.setLiked(video, liked = true)
            assertTrue(videos.video(video.id).liked, "optimistic update is visible before the request runs")
            likes.setLiked(video, liked = false)
            runCurrent()
            advanceUntilIdle()

            val sent = api.requests.single()
            assertEquals(SetLikeState(7), sent.endpoint)
            assertEquals(LikeBody(liked = false), sent.body)
            assertEquals(MutationStatus.Succeeded, likes.status(video.id).value)
            assertTrue(store.loadAll().isEmpty(), "finished likes are removed from the store")
        }

    @Test
    fun `the codec round-trips the endpoint, body and body type`() {
        val payload = SetLikeStateCodec.encode(SetLikeState(7), LikeBody(liked = true))
        val decoded = SetLikeStateCodec.decode(payload)

        assertEquals(SetLikeState(7), decoded.endpoint)
        assertEquals(LikeBody(liked = true), decoded.body)
        assertEquals(typeInfo<LikeBody>(), decoded.bodyType)
    }
}
