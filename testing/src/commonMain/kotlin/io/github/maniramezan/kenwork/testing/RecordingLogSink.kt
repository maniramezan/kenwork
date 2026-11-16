package io.github.maniramezan.kenwork.testing

import io.github.maniramezan.kenwork.network.KenworkLogger
import io.github.maniramezan.kenwork.network.LogCategory
import io.github.maniramezan.kenwork.network.LogLevel
import io.github.maniramezan.kenwork.network.StructuredLogSink

/**
 * A [StructuredLogSink] that records every line [KenworkLogger] emits, attributes included, so
 * tests can assert on logging (e.g. that nothing sensitive is logged). Prefer [withRecordedLogs],
 * which installs one and restores the previous logger state afterwards.
 */
public class RecordingLogSink : StructuredLogSink {
    /** One recorded log line. */
    public data class Entry(
        public val level: LogLevel,
        public val category: LogCategory,
        public val message: String,
        public val throwable: Throwable?,
        public val attributes: Map<String, Any?>,
    )

    private val lock = Any()
    private val recorded = mutableListOf<Entry>()

    /** A snapshot of every line recorded so far, in order. */
    public val entries: List<Entry>
        get() = synchronized(lock) { recorded.toList() }

    override fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ): Unit = log(level, category, message, throwable, emptyMap())

    override fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
        attributes: Map<String, Any?>,
    ) {
        synchronized(lock) { recorded += Entry(level, category, message, throwable, attributes) }
    }
}

/**
 * Routes [KenworkLogger] to a fresh [RecordingLogSink] at [level] for the duration of [block],
 * then restores the previous sink and level — even if [block] throws.
 *
 * [KenworkLogger] is process-global, so avoid running tests that use this in parallel with other
 * tests that log.
 */
public inline fun <T> withRecordedLogs(
    level: LogLevel = LogLevel.DEBUG,
    block: (RecordingLogSink) -> T,
): T {
    val previousSink = KenworkLogger.sink
    val previousLevel = KenworkLogger.level
    val sink = RecordingLogSink()
    KenworkLogger.sink = sink
    KenworkLogger.level = level
    try {
        return block(sink)
    } finally {
        KenworkLogger.sink = previousSink
        KenworkLogger.level = previousLevel
    }
}
