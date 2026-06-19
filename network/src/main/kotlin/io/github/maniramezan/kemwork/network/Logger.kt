package io.github.maniramezan.kemwork.network

import android.util.Log

/** Verbosity levels for [KemworkLogger]. Mirrors SwiftyNetwork's `LogLevel`. */
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

/** Destination for log lines. Replace [KemworkLogger.sink] to route logs elsewhere. */
public fun interface LogSink {
    public fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    )
}

/**
 * Lightweight, dependency-free logging facade for the library. Mirrors SwiftyNetwork's `Logger`.
 *
 * Defaults to [LogLevel.WARNING] and an [android.util.Log]-backed sink; both are replaceable so
 * consumers can raise verbosity or forward to their own logging stack (Timber, etc.).
 */
public object KemworkLogger {
    @Volatile
    public var level: LogLevel = LogLevel.WARNING

    @Volatile
    public var sink: LogSink = AndroidLogSink

    public fun debug(
        message: String,
        category: LogCategory = LogCategory.NETWORK,
    ): Unit = emit(LogLevel.DEBUG, category, message, null)

    public fun info(
        message: String,
        category: LogCategory = LogCategory.NETWORK,
    ): Unit = emit(LogLevel.INFO, category, message, null)

    public fun warning(
        message: String,
        category: LogCategory = LogCategory.NETWORK,
    ): Unit = emit(LogLevel.WARNING, category, message, null)

    public fun error(
        message: String,
        throwable: Throwable? = null,
        category: LogCategory = LogCategory.NETWORK,
    ): Unit = emit(LogLevel.ERROR, category, message, throwable)

    private fun emit(
        lineLevel: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        if (level == LogLevel.OFF || lineLevel.ordinal > level.ordinal) return
        sink.log(lineLevel, category, message, throwable)
    }
}

/** Default [LogSink] writing to Android's Logcat under the `kemwork.<category>` tag. */
public object AndroidLogSink : LogSink {
    override fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        val tag = "kemwork.${category.name.lowercase()}"
        when (level) {
            LogLevel.OFF -> Unit
            LogLevel.ERROR -> Log.e(tag, message, throwable)
            LogLevel.WARNING -> Log.w(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
        }
    }
}
