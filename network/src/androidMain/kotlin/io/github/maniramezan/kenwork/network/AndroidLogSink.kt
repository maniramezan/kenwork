package io.github.maniramezan.kenwork.network

import android.util.Log

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

internal actual fun platformLogSink(): LogSink = AndroidLogSink
