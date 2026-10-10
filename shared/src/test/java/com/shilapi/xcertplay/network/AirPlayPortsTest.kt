package com.shilapi.xcertplay.network

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPortsTest {
    @Test fun theConventionalPortIsKeptWhenItIsFree() {
        assertEquals(7000, AirPlayPorts.choose(7000, isFree = { true }, freePort = { 4711 }))
    }

    /** Losing an on-car attempt to a port something else holds would be a wasted trip. */
    @Test fun aBusyPortGivesWayToAFreeOneInsteadOfFailingTheRun() {
        assertEquals(4711, AirPlayPorts.choose(7000, isFree = { false }, freePort = { 4711 }))
    }

    @Test fun aBoundPortIsNotFreeAndTheEphemeralOneAfterItIs() {
        ServerSocket(0).use { held ->
            assertFalse(AirPlayPorts.isFree(held.localPort))

            val chosen = AirPlayPorts.choose(held.localPort)

            assertNotEquals(held.localPort, chosen)
            assertTrue(AirPlayPorts.isFree(chosen))
        }
    }
}
