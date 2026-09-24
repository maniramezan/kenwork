package io.github.maniramezan.kenwork.cache

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileSystemCacheTest {
    private val dir: File = createTempDirectory("fscache").toFile()
    private val key = CacheKey("user:1:profile")

    private companion object {
        /** `sha256("user:1:profile")`, i.e. the on-disk name of [key]'s entry. */
        const val KEY_SHA256 = "ffb422ad0dd2dd8fddf487a7c23642dfdd793e642fc1b6c79cea94d8cfd8a188"
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun TestScope.cache(now: () -> Long = { 0L }): FileSystemCache<String> =
        FileSystemCache(
            directory = dir,
            encode = { it },
            decode = { it },
            ioContext = UnconfinedTestDispatcher(testScheduler),
            currentTimeMillis = now,
        )

    @Test
    fun `persists a value and timestamp across instances`() =
        runTest {
            cache(now = { 42L }).setValue("ada", key)

            // A brand-new instance over the same directory reads the persisted entry.
            val reopened = cache()
            assertEquals("ada", reopened.value(key))
            assertEquals(42L, reopened.timestamp(key))
            assertEquals(CacheEntry("ada", 42L), reopened.entry(key))
        }

    @Test
    fun `value is null when absent`() =
        runTest {
            assertNull(cache().value(key))
            assertNull(cache().entry(key))
        }

    @Test
    fun `removeValue and removeAll delete persisted entries`() =
        runTest {
            val cache = cache()
            cache.setValue("a", key)
            cache.setValue("b", CacheKey("other"))

            cache.removeValue(key)
            assertNull(cache.value(key))
            assertEquals("b", cache.value(CacheKey("other")))

            cache.removeAll()
            assertNull(cache.value(CacheKey("other")))
        }

    @Test
    fun `a malformed file reads back as null`() =
        runTest {
            val cache = cache()
            cache.setValue("a", key)
            // Corrupt the underlying file (no timestamp/newline structure).
            dir.listFiles()!!.first().writeText("garbage-without-newline")
            assertNull(cache.value(key))
        }

    @Test
    fun `preserves values containing newlines`() =
        runTest {
            val cache = cache(now = { 7L })
            cache.setValue("line1\nline2\nline3", key)
            assertEquals(CacheEntry("line1\nline2\nline3", 7L), cache.entry(key))
        }

    @Test
    fun `writes replace the entry without leaving temporary files`() =
        runTest {
            val cache = cache()

            cache.setValue("first", key)
            cache.setValue("second", key)

            assertEquals("second", cache.value(key))
            assertEquals(1, dir.listFiles { file -> file.name.endsWith(".kenc") }?.size)
            assertEquals(0, dir.listFiles { file -> file.name.endsWith(".tmp") }?.size)
        }

    @Test
    fun `emits change events on mutation`() =
        runTest {
            val cache = cache()
            val changes = mutableListOf<CacheChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                cache.changes().collect { changes += it }
            }

            cache.setValue("a", key)
            cache.removeValue(key)
            cache.setValue("b", key)
            cache.removeAll()

            assertEquals(
                listOf(
                    CacheChange.Updated(key),
                    CacheChange.Removed(key),
                    CacheChange.Updated(key),
                    CacheChange.Cleared,
                ),
                changes,
            )
        }

    @Test
    fun `layers under memory and promotes preserving the timestamp`() =
        runTest {
            val persistent = cache(now = { 99L })
            persistent.setValue("disk", key)
            val memory = InMemoryCache<String>(currentTimeMillis = { 5L })
            val layered = LayeredCache(memory, persistent)

            // Memory miss falls through to disk and promotes with the original timestamp.
            assertEquals(CacheEntry("disk", 99L), layered.entry(key))
            assertEquals(99L, memory.timestamp(key))
        }

    @Test
    fun `entry file names are the lowercase hex SHA-256 of the key`() =
        runTest {
            // Pinned so a hashing change can never orphan caches already on users' devices.
            cache().setValue("ada", key)
            assertEquals(listOf("$KEY_SHA256.kenc"), dir.list()?.toList())
        }

    @Test
    fun `removeAll also deletes orphaned temporary files but leaves foreign files`() =
        runTest {
            val cache = cache()
            cache.setValue("ada", key)
            val orphan = File(dir, "$KEY_SHA256.kenc123.tmp").apply { writeText("partial") }
            val foreign = File(dir, "notes.tmp").apply { writeText("keep") }

            cache.removeAll()

            assertFalse(orphan.exists())
            assertTrue(foreign.exists())
            assertEquals(listOf("notes.tmp"), dir.list()?.toList())
        }
}
