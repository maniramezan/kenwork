package io.github.maniramezan.kemwork.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {
    @Test
    fun `status is unknown until monitoring starts`() {
        val monitor = NetworkMonitor(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(NetworkReachability.UNKNOWN, monitor.status)
    }

    @Test
    fun `start resolves reachability away from unknown`() {
        val monitor = NetworkMonitor(ApplicationProvider.getApplicationContext<Context>())
        monitor.start()
        assertNotEquals(NetworkReachability.UNKNOWN, monitor.status)
        monitor.stop()
    }
}
