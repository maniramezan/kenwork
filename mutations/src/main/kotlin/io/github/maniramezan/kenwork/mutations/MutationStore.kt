package io.github.maniramezan.kenwork.mutations

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pluggable persistence for enqueued mutations, keyed by [MutationRecord.id].
 *
 * [MutationQueue] calls [save] when a mutation (with a [MutationCodec]) is enqueued, [remove]
 * once it reaches a terminal outcome (or is coalesced away by a newer enqueue for the same key),
 * and [loadAll] on [MutationQueue.restore] to replay whatever didn't finish before the process
 * died.
 *
 * [InMemoryMutationStore] is the shipped default — it does not survive process death. To make
 * queued mutations durable, implement this interface backed by SQLDelight, Room, DataStore, or
 * even a flat file: every [MutationRecord] field is a primitive/string, so any of those work
 * without extra serialization glue.
 */
public interface MutationStore {
    /** Persists (or overwrites) [record]. */
    public suspend fun save(record: MutationRecord)

    /** Removes the record with the given [id], if present. */
    public suspend fun remove(id: String)

    /** All currently persisted records, in no particular order. */
    public suspend fun loadAll(): List<MutationRecord>
}

/**
 * The default [MutationStore]: an in-memory map. Mutations enqueued with a [MutationCodec] are
 * still tracked (so [MutationQueue.restore] works within the same process, e.g. after recreating
 * a `MutationQueue`), but everything is lost on process death — use this when you don't need
 * mutations to survive a relaunch, or as a reference implementation when writing a durable one.
 */
public class InMemoryMutationStore : MutationStore {
    private val mutex = Mutex()
    private val records = LinkedHashMap<String, MutationRecord>()

    override suspend fun save(record: MutationRecord) {
        mutex.withLock { records[record.id] = record }
    }

    override suspend fun remove(id: String) {
        mutex.withLock { records.remove(id) }
    }

    override suspend fun loadAll(): List<MutationRecord> = mutex.withLock { records.values.toList() }
}
