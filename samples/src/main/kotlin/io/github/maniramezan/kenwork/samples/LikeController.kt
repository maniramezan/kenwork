package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.mutations.DecodedMutation
import io.github.maniramezan.kenwork.mutations.MutationCodec
import io.github.maniramezan.kenwork.mutations.MutationKey
import io.github.maniramezan.kenwork.mutations.MutationQueue
import io.github.maniramezan.kenwork.mutations.MutationStatus
import io.github.maniramezan.kenwork.mutations.enqueue
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Step 4 — fire-and-forget writes: update the UI optimistically, queue the request, retry it in
// the background, and survive process death.

/**
 * Likes/unlikes videos optimistically.
 *
 * The local copy changes immediately (so every [VideoRepository.observe] collector re-renders),
 * while the request runs on the [queue]'s app-lifetime scope. Rapid toggles coalesce into a
 * single request for the final state. Construct the queue with `codecs = listOf(SetLikeStateCodec)`
 * and a durable store, and call `queue.restore()` at startup to replay unfinished likes.
 */
class LikeController(
    private val queue: MutationQueue,
    private val videos: VideoRepository,
) {
    /** Applies [liked] locally and enqueues the matching request; returns without waiting for it. */
    suspend fun setLiked(
        video: Video,
        liked: Boolean,
    ) {
        videos.store(video.copy(liked = liked))
        queue.enqueue(keyFor(video.id), SetLikeState(video.id), LikeBody(liked), codec = SetLikeStateCodec)
    }

    /**
     * The request outcome for [videoId]. On [MutationStatus.Failed], restore the server's truth,
     * e.g. with [VideoRepository.refresh], and tell the user.
     */
    fun status(videoId: Int): StateFlow<MutationStatus?> = queue.statusFlow(keyFor(videoId))

    companion object {
        fun keyFor(videoId: Int): MutationKey = MutationKey.of("like", "video", videoId)
    }
}

/**
 * Persists [SetLikeState] + [LikeBody] as JSON so a queued like survives process death. Its [id]
 * is stored with every record: never change it once shipped.
 */
object SetLikeStateCodec : MutationCodec<LikeBody> {
    override val id: String = "samples.set-like-state.v1"

    override fun encode(
        endpoint: NetworkEndpoint,
        body: LikeBody?,
    ): String {
        val videoId = (endpoint as SetLikeState).videoId
        return Json.encodeToString(Payload.serializer(), Payload(videoId, liked = body?.liked ?: false))
    }

    override fun decode(payload: String): DecodedMutation<LikeBody> {
        val decoded = Json.decodeFromString(Payload.serializer(), payload)
        // The reified factory records LikeBody's TypeInfo, which the client needs to serialize the
        // body when the mutation is replayed after a restart.
        return DecodedMutation(SetLikeState(decoded.videoId), LikeBody(decoded.liked))
    }

    @Serializable
    private data class Payload(
        val videoId: Int,
        val liked: Boolean,
    )
}
