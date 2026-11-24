package dev.lemon.rex

import org.junit.Assert.*
import org.junit.Test

class NetworkRouteTrackerTest {
    @Test
    fun initialAndDuplicateCallbacksDoNotRestartAHealthyConnection() {
        val tracker = NetworkRouteTracker()
        val route = NetworkRoute("wifi", 1, true)
        assertFalse(tracker.update(route)!!.retry)
        assertNull(tracker.update(route))
    }

    @Test
    fun networkAndVpnUnderlyingTransportChangesTriggerRetry() {
        val tracker = NetworkRouteTracker()
        tracker.update(NetworkRoute("vpn", 9, true))
        assertTrue(tracker.update(NetworkRoute("vpn", 10, true))!!.reroute)
        assertTrue(tracker.update(NetworkRoute("cell", 2, true))!!.retry)
    }

    @Test
    fun losingAndRecoveringConnectivityRetriesOnce() {
        val tracker = NetworkRouteTracker()
        tracker.update(NetworkRoute("wifi", 1, true))
        assertFalse(tracker.update(NetworkRoute(null))!!.available)
        assertTrue(tracker.update(NetworkRoute("wifi2", 1, true))!!.retry)
        assertNull(tracker.update(NetworkRoute("wifi2", 1, true)))
    }

    @Test
    fun validationRecoveryCanRetryWithoutForcingAHealthyConnectionToMove() {
        val tracker = NetworkRouteTracker()
        tracker.update(NetworkRoute("wifi", 1, false))
        val next = tracker.update(NetworkRoute("wifi", 1, true))!!
        assertTrue(next.retry)
        assertFalse(next.reroute)
    }
}
