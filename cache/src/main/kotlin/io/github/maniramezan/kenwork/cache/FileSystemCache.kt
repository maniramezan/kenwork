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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * File I/O runs on [ioContext] and is guarded by a [Mutex]. Writes use a same-directory temporary
 * file and atomic replacement, so a process interruption leaves either the old complete entry or
 * the new complete entry. Only files this cache wrote (suffixed [SUFFIX]) are touched, so the
 * directory may be shared. A malformed or unreadable file reads back as `null` rather than
 * throwing.
 *
 * When [maxSizeBytes] is set (default 50 MB), writes evict the oldest entries by last-modified
 * time until total disk usage for owned cache files is within the limit. Pass `maxSizeBytes = null`
 * to disable the limit (unbounded growth). Eviction emits [CacheChange.Removed] for keys this
 * instance has read or written; files from an earlier process cannot be mapped back from their
 * hashed names until they are read.
 *
 * @param directory the storage directory; created on first write.
 * @param encode serializes a value to text.
 * @param decode parses text produced by [encode] back into a value.
 * @param ioContext context for blocking file I/O; defaults to [Dispatchers.IO].
 * @param currentTimeMillis time source, injectable for deterministic tests.
 * @param maxSizeBytes maximum disk usage in bytes for owned cache files. Defaults to 50 MB.
 *   Pass `null` for unbounded (not recommended for production).
 */
public class FileSystemCache<V : Any>(
    private val directory: File,
    private val encode: (V) -> String,
    private val decode: (String) -> V,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val maxSizeBytes: Long? = DEFAULT_MAX_SIZE_BYTES,
) : TimestampedCache<V>,
    PersistentCache<V> {
    /** Binary-compatibility shim for callers compiled before [maxSizeBytes] was introduced. */
    @Deprecated("Binary-compatibility shim; use the primary constructor.", level = DeprecationLevel.HIDDEN)
    public constructor(
        directory: File,
        encode: (V) -> String,
        decode: (String) -> V,
        ioContext: CoroutineContext = Dispatchers.IO,
        currentTimeMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        directory = directory,
        encode = encode,
        decode = decode,
        ioContext = ioContext,
        currentTimeMillis = currentTimeMillis,
        maxSizeBytes = DEFAULT_MAX_SIZE_BYTES,
    )

    init {
        require(maxSizeBytes == null || maxSizeBytes > 0) { "maxSizeBytes must be positive or null, got $maxSizeBytes" }
    }

    private val mutex = Mutex()
    private val knownKeys = mutableMapOf<String, CacheKey>()

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
        val evicted = withContext(ioContext) { mutex.withLock { writeEntry(key, value, timestamp) } }
        changeFlow.tryEmit(CacheChange.Updated(key))
        evicted.forEach { changeFlow.tryEmit(CacheChange.Removed(it)) }
    }

    override suspend fun removeValue(key: CacheKey) {
        val removed =
            withContext(ioContext) {
                mutex.withLock {
                    val file = fileFor(key)
                    file.delete().also { if (it) knownKeys.remove(file.nameWithoutExtension) }
                }
            }
        if (removed) changeFlow.tryEmit(CacheChange.Removed(key))
    }

    override suspend fun removeAll() {
        withContext(ioContext) {
            mutex.withLock {
                directory.listFiles { file -> file.isOwnedEntryOrOrphanedTemp() }?.forEach { it.delete() }
                knownKeys.clear()
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
        }.getOrNull()?.also { knownKeys[file.nameWithoutExtension] = key }
    }

    private fun writeEntry(
        key: CacheKey,
        value: V,
        timestamp: Long,
    ): List<CacheKey> {
        check(directory.exists() || directory.mkdirs()) { "Unable to create cache directory: $directory" }
        val destination = fileFor(key)
        val temporary = File.createTempFile(destination.name, TEMPORARY_SUFFIX, directory)
        try {
            temporary.writeText("$timestamp\n${encode(value)}")
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }

        knownKeys[destination.nameWithoutExtension] = key
        return evictIfNeeded()
    }

    private fun evictIfNeeded(): List<CacheKey> {
        val limit = maxSizeBytes ?: return emptyList()
        val ownedFiles =
            directory
                .listFiles { file -> file.name.endsWith(SUFFIX) }
                ?.sortedBy { it.lastModified() }
                ?: return emptyList()

        var totalBytes = ownedFiles.sumOf { it.length() }
        if (totalBytes <= limit) return emptyList()

        val evicted = mutableListOf<CacheKey>()
        for (file in ownedFiles) {
            if (totalBytes <= limit) break
            val fileSize = file.length()
            if (file.delete()) {
                totalBytes -= fileSize
                knownKeys.remove(file.nameWithoutExtension)?.let(evicted::add)
            }
        }
        return evicted
    }

    private fun fileFor(key: CacheKey): File = File(directory, hash(key.rawValue) + SUFFIX)

    /**
     * Whether this file is an entry this cache wrote, or a temporary left behind when the process
     * died between creating it and the atomic move (temporaries are named `<entry>.kenc<n>.tmp`).
     */
    private fun File.isOwnedEntryOrOrphanedTemp(): Boolean =
        name.endsWith(SUFFIX) || (name.endsWith(TEMPORARY_SUFFIX) && name.contains(SUFFIX))

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and BYTE_MASK
            hex[index * 2] = HEX_DIGITS[value ushr NIBBLE_BITS]
            hex[index * 2 + 1] = HEX_DIGITS[value and NIBBLE_MASK]
        }
        return String(hex)
    }

    private companion object {
        private const val SUFFIX = ".kenc"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val CHANGE_BUFFER_CAPACITY = 64
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val BYTE_MASK = 0xFF
        private const val NIBBLE_MASK = 0x0F
        private const val NIBBLE_BITS = 4
        private const val DEFAULT_MAX_SIZE_BYTES = 50 * 1024 * 1024L
    }
}
