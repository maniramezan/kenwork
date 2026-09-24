package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.network.HttpMethod
import io.github.maniramezan.kenwork.network.NetworkEndpoint
import kotlinx.serialization.Serializable

// Step 1 — describe the API as data: one small type per route, plus @Serializable DTOs.

internal const val API_BASE_URL = "https://api.example.com"

@Serializable
data class Video(
    val id: Int,
    val title: String,
    val liked: Boolean = false,
)

@Serializable
data class LikeBody(
    val liked: Boolean,
)

/** `GET /v1/videos/{id}` — idempotent, so the default retry policy may retry it. */
data class GetVideo(
    val id: Int,
) : NetworkEndpoint {
    override val baseUrl: String = API_BASE_URL
    override val path: String = "v1/videos/$id"
    override val method: HttpMethod = HttpMethod.GET
}

/**
 * `PUT /v1/videos/{id}/like` with a [LikeBody]. It *sets* a desired state rather than toggling,
 * so replaying it is harmless — which is what makes it safe to queue and retry.
 */
data class SetLikeState(
    val videoId: Int,
) : NetworkEndpoint {
    override val baseUrl: String = API_BASE_URL
    override val path: String = "v1/videos/$videoId/like"
    override val method: HttpMethod = HttpMethod.PUT
}
