package io.github.maniramezan.kenwork.samples

/** Where the app keeps its credentials; e.g. backed by encrypted storage and your auth API. */
interface TokenSource {
    /** The access token to start with (may be expired; a 401 triggers [refresh]). */
    val accessToken: String

    /** Mints a new access token, or returns `null` when the session can't be renewed. */
    suspend fun refresh(): String?
}
