package io.github.maniramezan.kenwork.samples

import io.github.maniramezan.kenwork.cache.CacheKey
import io.github.maniramezan.kenwork.cache.CachePolicy
import io.github.maniramezan.kenwork.cache.FileSystemCache
import io.github.maniramezan.kenwork.cache.InMemoryCache
import io.github.maniramezan.kenwork.cache.LayeredCache
import io.github.maniramezan.kenwork.network.DefaultKenworkJson
import io.github.maniramezan.kenwork.network.NetworkDataSource
import io.github.maniramezan.kenwork.repository.CacheBasedLocalDataSource
import io.github.maniramezan.kenwork.repository.GenericRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.CoroutineContext

// Step 3 — put a repository in front of the client: memory + disk cache, single-flight loads,
// and a reactive stream the UI can collect.

/**
 * Offline-first access to [Video]s.
 *
 * Reads go memory → disk → network. Network results are written through to both cache tiers, so
 * they survive process death, and concurrent loads of the same video share one request.
 *
 * @param cacheDirectory an app-private directory, e.g. `File(context.cacheDir, "videos")`. Scope it
 *   per signed-in account and delete it on sign-out (see `docs/security.md`).
 */
class VideoRepository(
    network: NetworkDataSource,
    cacheDirectory: File,
    json: Json = DefaultKenworkJson,
    ioContext: CoroutineContext = Dispatchers.IO,
    scope: CoroutineScope? = null,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val cache =
        LayeredCache(
            memory = InMemoryCache<Video>(maxSize = MEMORY_ENTRIES, currentTimeMillis = currentTimeMillis),
            persistent =
                FileSystemCache(
                    directory = cacheDirectory,
                    encode = { json.encodeToString(Video.serializer(), it) },
                    decode = { json.decodeFromString(Video.serializer(), it) },
                    ioContext = ioContext,
                    currentTimeMillis = currentTimeMillis,
                ),
        )

    private val repository =
        GenericRepository<Video>(
            networkDataSource = network,
            localDataSource = CacheBasedLocalDataSource(cache),
            currentTimeMillis = currentTimeMillis,
            scope = scope,
        )

    /** Returns the video, from cache when it is younger than [maxAgeMillis]. */
    suspend fun video(
        id: Int,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    ): Video = repository.fetch(GetVideo(id), keyFor(id), CachePolicy.ReturnCacheIfNotExpired(maxAgeMillis))

    /** Bypasses the cache (e.g. pull-to-refresh); observers of [observe] receive the result. */
    suspend fun refresh(id: Int): Video = repository.fetch(GetVideo(id), keyFor(id), CachePolicy.ReloadIgnoringCache)

    /**
     * Emits the cached (or loaded) video, then every later change — a refresh, an optimistic
     * update from [store], or `null` when it is evicted/cleared.
     */
    fun observe(id: Int): Flow<Video?> = repository.streamOrNull(GetVideo(id), keyFor(id), CachePolicy.ReturnCacheElseLoad)

    /** Writes [video] locally without a network call, e.g. to apply an optimistic update. */
    suspend fun store(video: Video): Unit = cache.setValue(video, keyFor(video.id))

    /** Clears both cache tiers, e.g. on sign-out. */
    suspend fun clear(): Unit = cache.removeAll()

    /** Cancels in-flight loads when the repository owns its scope. */
    fun close(): Unit = repository.close()

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS: Long = 5 * 60 * 1_000L
        private const val MEMORY_ENTRIES = 100

        /** One key per video; include every parameter that changes the response. */
        fun keyFor(id: Int): CacheKey = CacheKey.endpoint("v1/videos/$id")
    }
}
