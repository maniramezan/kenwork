package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * A durable [PersistentCache] that stores each entry as a file under [directory].
 *
 * Mirrors SwiftyNetwork's disk-backed cache. Values are turned into text by the caller-supplied
 * [encode]/[decode] pair (e.g. `kotlinx.serialization`'s `Json.encodeToString`/`decodeFromString`),
 * so `:cache` stays free of any serialization dependency. Each file stores the epoch-millisecond
 * timestamp on its first line followed by the encoded value, so it is a [TimestampedCache] and
 * promotes into a memory layer without losing age (see [LayeredCache]).
 *
 * File I/O runs on [ioContext] and is guarded by a [Mutex]; only files this cache wrote (suffixed
 * [SUFFIX]) are touched, so the directory may be shared. A malformed or unreadable file reads back
 * as `null` rather than throwing.
 *
 * @param directory the storage directory; created on first write.
 * @param encode serializes a value to text.
 * @param decode parses text produced by [encode] back into a value.
 * @param ioContext context for blocking file I/O; defaults to [Dispatchers.IO].
 * @param currentTimeMillis time source, injectable for deterministic tests.
 */
public class FileSystemCache<V : Any>(
    private val directory: File,
    private val encode: (V) -> String,
    private val decode: (String) -> V,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : TimestampedCache<V>,
    PersistentCache<V> {
    private val mutex = Mutex()

    private val changeFlow =
        MutableSharedFlow<CacheChange>(
            extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun changes(): Flow<CacheChange> = changeFlow.asSharedFlow()

    override suspend fun value(key: CacheKey): V? = entry(key)?.value

    override suspend fun timestamp(key: CacheKey): Long? = entry(key)?.timestamp

    override suspend fun entry(key: CacheKey): CacheEntry<V>? = withContext(ioContext) { mutex.withLock { readEntry(key) } }

    override suspend fun setValue(
        value: V,
        key: CacheKey,
    ): Unit = setValue(value, key, currentTimeMillis())

    override suspend fun setValue(
        value: V,
        key: CacheKey,
        timestamp: Long,
    ) {
        withContext(ioContext) { mutex.withLock { writeEntry(key, value, timestamp) } }
        changeFlow.tryEmit(CacheChange.Updated(key))
    }

    override suspend fun removeValue(key: CacheKey) {
        val removed = withContext(ioContext) { mutex.withLock { fileFor(key).delete() } }
        if (removed) changeFlow.tryEmit(CacheChange.Removed(key))
    }

    override suspend fun removeAll() {
        withContext(ioContext) {
            mutex.withLock {
                directory.listFiles { file -> file.name.endsWith(SUFFIX) }?.forEach { it.delete() }
            }
        }
        changeFlow.tryEmit(CacheChange.Cleared)
    }

    private fun readEntry(key: CacheKey): CacheEntry<V>? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText()
            val separator = text.indexOf('\n')
            val timestamp = text.substring(0, separator).toLong()
            CacheEntry(decode(text.substring(separator + 1)), timestamp)
        }.getOrNull()
    }

    private fun writeEntry(
        key: CacheKey,
        value: V,
        timestamp: Long,
    ) {
        if (!directory.exists()) directory.mkdirs()
        fileFor(key).writeText("$timestamp\n${encode(value)}")
    }

    private fun fileFor(key: CacheKey): File = File(directory, hash(key.rawValue) + SUFFIX)

    private fun hash(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val SUFFIX = ".kenc"
        private const val CHANGE_BUFFER_CAPACITY = 64
    }
}
