package io.github.maniramezan.kemwork.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerTest {
    private class Capture : LogSink {
        val messages = mutableListOf<String>()
        val categories = mutableListOf<LogCategory>()

        override fun log(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
        ) {
            messages += message
            categories += category
        }
    }

    @AfterTest
    fun reset() {
        KemworkLogger.level = LogLevel.WARNING
        KemworkLogger.sink = AndroidLogSink
    }

    @Test
    fun `default level emits warning and error only`() {
        val capture = Capture()
        KemworkLogger.sink = capture
        KemworkLogger.level = LogLevel.WARNING

        KemworkLogger.debug("d")
        KemworkLogger.info("i")
        KemworkLogger.warning("w")
        KemworkLogger.error("e")

        assertEquals(listOf("w", "e"), capture.messages)
    }

    @Test
    fun `off silences everything`() {
        val capture = Capture()
        KemworkLogger.sink = capture
        KemworkLogger.level = LogLevel.OFF

        KemworkLogger.error("e")

        assertTrue(capture.messages.isEmpty())
    }

    @Test
    fun `debug level passes all categories`() {
        val capture = Capture()
        KemworkLogger.sink = capture
        KemworkLogger.level = LogLevel.DEBUG

        KemworkLogger.debug("d", LogCategory.CACHE)
        KemworkLogger.info("i", LogCategory.AUTH)

        assertEquals(listOf(LogCategory.CACHE, LogCategory.AUTH), capture.categories)
    }

    @Test
    fun `android sink handles every level without throwing`() {
        // android.util.Log is stubbed to return defaults in plain JVM unit tests.
        for (level in LogLevel.entries) {
            AndroidLogSink.log(level, LogCategory.NETWORK, "msg", null)
        }
    }
}
