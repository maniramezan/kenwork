package io.github.maniramezan.kenwork.network

import android.util.Log

/** Verbosity levels for [KenworkLogger]. Mirrors SwiftyNetwork's `LogLevel`. */
public enum class LogLevel {
    OFF,
    ERROR,
    WARNING,
    INFO,
    DEBUG,
}

/** Functional area a log line belongs to. Mirrors SwiftyNetwork's `Logger.Category`. */
public enum class LogCategory {
    NETWORK,
    CACHE,
    AUTH,
    REPOSITORY,
    SECURITY,
}

/** Destination for log lines. Replace [KenworkLogger.sink] to route logs elsewhere. */
public fun interface LogSink {
    public fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    )
}

/**
 * An optional extension of [LogSink] for destinations that want structured key-value attributes
 * alongside the formatted [message] — e.g. to populate OpenTelemetry log record `Attributes`, or any
 * other structured-logging backend, instead of only a flat string.
 *
 * This is additive: existing [LogSink] implementations are unaffected and keep receiving [message]
 * exactly as before via the inherited `log(level, category, message, throwable)`. [KenworkLogger]
 * calls the structured overload only when [sink] happens to implement this interface, and this
 * interface's default implementation simply forwards to the plain [LogSink.log], so implementing
 * only [log] with 4 arguments remains a complete, valid [LogSink].
 */
public interface StructuredLogSink : LogSink {
    /**
     * As [LogSink.log], plus [attributes] — arbitrary key-value context for this log line (e.g.
     * `"http.status_code" to 503`, `"kenwork.endpoint_id" to "videos/:id"`). Values are left as `Any?`
     * rather than `String` so a bridge can pass them through to a structured logging API's native
     * attribute types without stringifying first.
     */
    public fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
        attributes: Map<String, Any?>,
    ) {
        log(level, category, message, throwable)
    }
}

/**
 * Lightweight, dependency-free logging facade for the library. Mirrors SwiftyNetwork's `Logger`.
 *
 * Defaults to [LogLevel.WARNING] and an [android.util.Log]-backed sink; both are replaceable so
 * consumers can raise verbosity or forward to their own logging stack (Timber, etc.). Every method
 * accepts an optional `attributes` map, forwarded to [sink] when it implements [StructuredLogSink]
 * (e.g. to populate OpenTelemetry log record `Attributes`); a plain [LogSink] simply never sees it.
 */
public object KenworkLogger {
    @Volatile
    public var level: LogLevel = LogLevel.WARNING

    @Volatile
    public var sink: LogSink = AndroidLogSink

    public fun debug(
        message: String,
        category: LogCategory = LogCategory.NETWORK,
        attributes: Map<String, Any?> = emptyMap(),
    ): Unit = emit(LogLevel.DEBUG, category, message, null, attributes)

    public fun info(
        message: String,
        category: LogCategory = LogCategory.NETWORK,
        attributes: Map<String, Any?> = emptyMap(),
    ): Unit = emit(LogLevel.INFO, category, message, null, attributes)

    public fun warning(
        message: String,
        category: LogCategory = LogCategory.NETWORK,
        attributes: Map<String, Any?> = emptyMap(),
    ): Unit = emit(LogLevel.WARNING, category, message, null, attributes)

    public fun error(
        message: String,
        throwable: Throwable? = null,
        category: LogCategory = LogCategory.NETWORK,
        attributes: Map<String, Any?> = emptyMap(),
    ): Unit = emit(LogLevel.ERROR, category, message, throwable, attributes)

    private fun emit(
        lineLevel: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
        attributes: Map<String, Any?>,
    ) {
        if (level == LogLevel.OFF || lineLevel.ordinal > level.ordinal) return
        val currentSink = sink
        if (currentSink is StructuredLogSink) {
            currentSink.log(lineLevel, category, message, throwable, attributes)
        } else {
            currentSink.log(lineLevel, category, message, throwable)
        }
    }
}

/** Default [LogSink] writing to Android's Logcat under the `kenwork.<category>` tag. */
public object AndroidLogSink : LogSink {
    override fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        val tag = "kenwork.${category.name.lowercase()}"
        when (level) {
            LogLevel.OFF -> Unit
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            LogLevel.WARNING -> Log.w(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
        }
    }
}
