package io.github.maniramezan.kenwork.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

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

    @Test
    fun `losing the default network reports unreachable without re-querying it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = assertNotNull(manager.activeNetwork)
        val validated = ShadowNetworkCapabilities.newInstance()
        shadowOf(validated).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(network, validated)
        val monitor = NetworkMonitor(context)
        monitor.start()
        assertEquals(NetworkReachability.REACHABLE, monitor.status)
        // The lost network can still look active to a synchronous query; the callback must not trust it.
        shadowOf(manager).networkCallbacks.forEach { it.onLost(network) }
        assertEquals(NetworkReachability.UNREACHABLE, monitor.status)
        monitor.stop()
    }
}
