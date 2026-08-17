package io.github.maniramezan.kenwork.network

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
        KenworkLogger.level = LogLevel.WARNING
        KenworkLogger.sink = AndroidLogSink
    }

    @Test
    fun `default level emits warning and error only`() {
        val capture = Capture()
        KenworkLogger.sink = capture
        KenworkLogger.level = LogLevel.WARNING

        KenworkLogger.debug("d")
        KenworkLogger.info("i")
        KenworkLogger.warning("w")
        KenworkLogger.error("e")

        assertEquals(listOf("w", "e"), capture.messages)
    }

    @Test
    fun `off silences everything`() {
        val capture = Capture()
        KenworkLogger.sink = capture
        KenworkLogger.level = LogLevel.OFF

        KenworkLogger.error("e")

        assertTrue(capture.messages.isEmpty())
    }

    @Test
    fun `debug level passes all categories`() {
        val capture = Capture()
        KenworkLogger.sink = capture
        KenworkLogger.level = LogLevel.DEBUG

        KenworkLogger.debug("d", LogCategory.CACHE)
        KenworkLogger.info("i", LogCategory.AUTH)

        assertEquals(listOf(LogCategory.CACHE, LogCategory.AUTH), capture.categories)
    }

    @Test
    fun `android sink handles every level without throwing`() {
        // android.util.Log is stubbed to return defaults in plain JVM unit tests.
        for (level in LogLevel.entries) {
            AndroidLogSink.log(level, LogCategory.NETWORK, "msg", null)
        }
    }

    private class CaptureStructured : StructuredLogSink {
        val attributes = mutableListOf<Map<String, Any?>>()
        val plainCalls = mutableListOf<String>()

        override fun log(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
        ) {
            plainCalls += message
        }

        override fun log(
            level: LogLevel,
            category: LogCategory,
            message: String,
            throwable: Throwable?,
            attributes: Map<String, Any?>,
        ) {
            this.attributes += attributes
        }
    }

    @Test
    fun `a StructuredLogSink receives attributes instead of the plain overload`() {
        val capture = CaptureStructured()
        KenworkLogger.sink = capture
        KenworkLogger.level = LogLevel.DEBUG

        KenworkLogger.info("status", attributes = mapOf("http.status_code" to 503))

        assertEquals(listOf(mapOf<String, Any?>("http.status_code" to 503)), capture.attributes)
        assertTrue(capture.plainCalls.isEmpty())
    }

    @Test
    fun `a plain LogSink still works and never sees attributes`() {
        val capture = Capture()
        KenworkLogger.sink = capture
        KenworkLogger.level = LogLevel.DEBUG

        KenworkLogger.warning("w", attributes = mapOf("k" to "v"))

        assertEquals(listOf("w"), capture.messages)
    }

    @Test
    fun `StructuredLogSink default log delegates to the plain overload`() {
        val capture =
            object : StructuredLogSink {
                val calls = mutableListOf<String>()

                override fun log(
                    level: LogLevel,
                    category: LogCategory,
                    message: String,
                    throwable: Throwable?,
                ) {
                    calls += message
                }
            }

        capture.log(LogLevel.INFO, LogCategory.NETWORK, "delegated", null, mapOf("a" to 1))

        assertEquals(listOf("delegated"), capture.calls)
    }
}
