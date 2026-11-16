package io.github.maniramezan.kenwork.mutations

import io.github.maniramezan.kenwork.network.KenworkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext

/**
 * A durable [MutationStore] that keeps every pending [MutationRecord] in one JSON [file], so queued
 * mutations survive process death and [MutationQueue.restore] can replay them.
 *
 * Suited to the small number of pending writes a mutation queue normally holds: the whole list is
 * read once, cached in memory, and rewritten on every change. Each write goes to a temporary file
 * in the same directory and atomically replaces [file], so an interruption leaves either the old
 * or the new list. The parent directory is created on first write.
 *
 * Use one instance per file. Several instances (or processes) writing the same file would
 * overwrite each other's changes. A file that cannot be read or parsed is logged and treated as
 * empty, and the next write replaces it.
 *
 * Records are stored as plain text. Keep [file] in app-private storage, and never put credentials
 * in a [MutationCodec] payload (see `docs/security.md`).
 *
 * @param file where records are stored, e.g. `File(context.filesDir, "kenwork/mutations.json")`.
 * @param ioContext context for blocking file I/O; defaults to [Dispatchers.IO].
 */
public class FileMutationStore(
    private val file: File,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : MutationStore {
    private val mutex = Mutex()

    /** The records in [file], loaded on first use; `null` until then. */
    private var records: LinkedHashMap<String, MutationRecord>? = null

    override suspend fun save(record: MutationRecord): Unit = update { it[record.id] = record }

    override suspend fun remove(id: String): Unit = update { it.remove(id) }

    override suspend fun removeByKey(key: String): Unit = update { records -> records.values.removeAll { it.key == key } }

    override suspend fun loadAll(): List<MutationRecord> = withContext(ioContext) { mutex.withLock { loaded().values.toList() } }

    /** Deletes [file] and forgets every record, e.g. on sign-out. */
    public suspend fun clear() {
        withContext(ioContext) {
            mutex.withLock {
                file.delete()
                records = LinkedHashMap()
            }
        }
    }

    private suspend fun update(change: (MutableMap<String, MutationRecord>) -> Unit) {
        withContext(ioContext) {
            mutex.withLock {
                val current = loaded()
                change(current)
                write(current.values.toList())
            }
        }
    }

    private fun loaded(): LinkedHashMap<String, MutationRecord> =
        records ?: LinkedHashMap<String, MutationRecord>().also { fresh ->
            read().forEach { fresh[it.id] = it }
            records = fresh
        }

    private fun read(): List<MutationRecord> {
        if (!file.exists()) return emptyList()
        return try {
            Json.decodeFromString(SERIALIZER, file.readText())
        } catch (error: Exception) {
            KenworkLogger.error("FileMutationStore could not read its file; starting empty", error)
            emptyList()
        }
    }

    private fun write(list: List<MutationRecord>) {
        val directory = requireNotNull(file.absoluteFile.parentFile) { "Mutation store file must have a parent directory" }
        check(directory.exists() || directory.mkdirs()) { "Unable to create directory: $directory" }
        val temporary = File.createTempFile(file.name, TEMPORARY_SUFFIX, directory)
        try {
            temporary.writeText(Json.encodeToString(SERIALIZER, list))
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        private val SERIALIZER = ListSerializer(MutationRecord.serializer())
        private const val TEMPORARY_SUFFIX = ".tmp"
    }
}
