package io.github.maniramezan.kenwork.network

import java.util.logging.Level
import java.util.logging.Logger

/**
 * The desktop JVM's default [LogSink]: `java.util.logging`, under the `kenwork.<category>` logger,
 * so lines reach whatever bridge the application installs (for example SLF4J's `jul-to-slf4j`).
 */
internal object JvmLogSink : LogSink {
    override fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable?,
    ) {
        val julLevel =
            when (level) {
                LogLevel.OFF -> return
                LogLevel.ERROR -> Level.SEVERE
                LogLevel.WARNING -> Level.WARNING
                LogLevel.INFO -> Level.INFO
                LogLevel.DEBUG -> Level.FINE
            }
        Logger.getLogger("kenwork.${category.name.lowercase()}").log(julLevel, message, throwable)
    }
}

internal actual fun platformLogSink(): LogSink = JvmLogSink
