package io.github.maniramezan.kenwork.network

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidLogSinkTest {
    @Test
    fun `Logcat is the Android default sink`() {
        assertEquals(AndroidLogSink, platformLogSink())
    }

    @Test
    fun `android sink handles every level without throwing`() {
        // android.util.Log is stubbed to return defaults in host tests.
        for (level in LogLevel.entries) {
            AndroidLogSink.log(level, LogCategory.NETWORK, "msg", null)
        }
    }
}
