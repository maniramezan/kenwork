package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.ApiClient
import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import io.ktor.util.reflect.TypeInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drains all pending work on [MutationQueue]'s background scope: a `backgroundScope`-launched
 * coroutine's *first* dispatch is only picked up by [runCurrent], so a bare [advanceUntilIdle]
 * (which otherwise fast-forwards delays/retries) can otherwise see an empty queue and return
 * immediately. Always settle with this instead of a bare `advanceUntilIdle()`.
 */
internal fun TestScope.settle() {
    runCurrent()
    advanceUntilIdle()
}

/** A minimal POST endpoint under test — non-idempotent, like `POST /videos/{id}/like`. */
internal data class SetLikeState(
    val videoId: Int,
) : NetworkEndpoint {
    override val baseUrl = "https://api.test"
    override val path = "videos/$videoId/like"
    override val method = HttpMethod.POST
}

@Serializable
internal data class LikeBody(
    val liked: Boolean,
)

/** One recorded call to [RecordingApiClient]. */
internal data class RecordedCall(
    val endpoint: NetworkEndpoint,
    val body: Any?,
)

/**
 * A fake [ApiClient] that records every call and delegates the outcome to [handler], letting
 * tests deterministically script transient failures / successes without a real (or mock-engine)
 * HTTP round trip.
 */
internal class RecordingApiClient(
    private val handler: suspend (call: RecordedCall, callIndex: Int) -> Unit = { _, _ -> },
) : ApiClient {
    private val counter = AtomicInteger(0)
    private val _calls = mutableListOf<RecordedCall>()
    val calls: List<RecordedCall> get() = synchronized(_calls) { _calls.toList() }

    override suspend fun <T> request(
        endpoint: NetworkEndpoint,
        body: Any?,
        bodyType: TypeInfo?,
        responseType: TypeInfo,
    ): T {
        val call = RecordedCall(endpoint, body)
        synchronized(_calls) { _calls.add(call) }
        val index = counter.getAndIncrement()
        handler(call, index)
        @Suppress("UNCHECKED_CAST")
        return Unit as T
    }
}

/** A [MutationCodec] for [SetLikeState] + [LikeBody], for persistence/replay tests. */
internal object SetLikeStateCodec : MutationCodec<LikeBody> {
    override val id: String = "set-like-state"

    override fun encode(
        endpoint: NetworkEndpoint,
        body: LikeBody?,
    ): String {
        val e = endpoint as SetLikeState
        return Json.encodeToString(Payload.serializer(), Payload(e.videoId, body?.liked ?: false))
    }

    override fun decode(payload: String): DecodedMutation<LikeBody> {
        val decoded = Json.decodeFromString(Payload.serializer(), payload)
        return DecodedMutation(SetLikeState(decoded.videoId), LikeBody(decoded.liked), null)
    }

    @Serializable
    private data class Payload(
        val videoId: Int,
        val liked: Boolean,
    )
}
