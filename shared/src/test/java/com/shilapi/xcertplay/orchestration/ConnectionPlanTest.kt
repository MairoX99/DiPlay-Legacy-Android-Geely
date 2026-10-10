package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder is what the driver reads to answer "where is it stuck", so every status the
 * controller can report is pinned to a rung here. A status added to [CarPlayStatus] without a
 * home breaks compilation of the mapping rather than silently landing on the last rung, which is
 * how the previous `else`-based version reported `Failed` and `ConnectingControl` as "session".
 */
class ConnectionPlanTest {

    /** Wired ladder: MFi runs before the iPhone is even discovered. */
    @Test
    fun wiredRungsRunFromAuthenticationToSession() {
        assertEquals(
            listOf(
                ConnectionStep.MFI,
                ConnectionStep.DEVICE,
                ConnectionStep.DATA_LINK,
                ConnectionStep.SESSION,
            ),
            connectionLadder(CarPlayTransport.WIRED),
        )
    }

    /**
     * Wireless ladder: the hotspot precedes bonding, and the network transport is attached before
     * the Bluetooth data channel opens. The two were previously listed the other way round.
     */
    @Test
    fun wirelessRungsPutNetworkBeforeTheBluetoothChannel() {
        val ladder = connectionLadder(CarPlayTransport.WIRELESS)
        assertTrue(ladder.indexOf(ConnectionStep.HOTSPOT) < ladder.indexOf(ConnectionStep.PAIRING))
        assertTrue(ladder.indexOf(ConnectionStep.PAIRING) < ladder.indexOf(ConnectionStep.NETWORK))
        assertTrue(ladder.indexOf(ConnectionStep.NETWORK) < ladder.indexOf(ConnectionStep.DATA_LINK))
        assertTrue(ladder.indexOf(ConnectionStep.DATA_LINK) < ladder.indexOf(ConnectionStep.SESSION))
    }

    @Test
    fun mfiComesFirstOnBothTransports() {
        assertEquals(ConnectionStep.MFI, connectionLadder(CarPlayTransport.WIRED).first())
        assertEquals(ConnectionStep.MFI, connectionLadder(CarPlayTransport.WIRELESS).first())
    }

    /** Every status, on the transport that reports it, pinned to the rung it belongs on. */
    @Test
    fun everyWiredStatusLandsOnItsRung() {
        val expected = mapOf(
            CarPlayStatus.DiscoveringMfi to ConnectionStep.MFI,
            CarPlayStatus.WaitingForMfi to ConnectionStep.MFI,
            CarPlayStatus.RequestingMfiPermission to ConnectionStep.MFI,
            CarPlayStatus.MfiReady to ConnectionStep.MFI,

            CarPlayStatus.DiscoveringIphone to ConnectionStep.DEVICE,
            CarPlayStatus.WaitingForIphone to ConnectionStep.DEVICE,
            CarPlayStatus.RequestingIphonePermission to ConnectionStep.DEVICE,
            CarPlayStatus.WaitingForReenumeration to ConnectionStep.DEVICE,
            CarPlayStatus.SelectingConfiguration to ConnectionStep.DEVICE,

            CarPlayStatus.OpeningDataPaths to ConnectionStep.DATA_LINK,
            CarPlayStatus.Pairing to ConnectionStep.DATA_LINK,
            CarPlayStatus.AttachingNetwork to ConnectionStep.DATA_LINK,

            CarPlayStatus.ConnectingControl to ConnectionStep.SESSION,
            CarPlayStatus.RunningControl to ConnectionStep.SESSION,
            CarPlayStatus.ControlEnded to ConnectionStep.SESSION,
        )
        expected.forEach { (status, rung) ->
            assertEquals("$status", rung, connectionStepOf(status, CarPlayTransport.WIRED))
        }
    }

    @Test
    fun everyWirelessStatusLandsOnItsRung() {
        val expected = mapOf(
            CarPlayStatus.DiscoveringMfi to ConnectionStep.MFI,
            CarPlayStatus.WaitingForMfi to ConnectionStep.MFI,
            CarPlayStatus.RequestingMfiPermission to ConnectionStep.MFI,
            CarPlayStatus.MfiReady to ConnectionStep.MFI,

            CarPlayStatus.StartingHotspot to ConnectionStep.HOTSPOT,
            CarPlayStatus.WaitingForPairedIphone to ConnectionStep.PAIRING,
            CarPlayStatus.AttachingNetwork to ConnectionStep.NETWORK,
            CarPlayStatus.ConnectingBluetooth to ConnectionStep.DATA_LINK,
            CarPlayStatus.RunningWireless to ConnectionStep.DATA_LINK,
            CarPlayStatus.WirelessActive to ConnectionStep.SESSION,
        )
        expected.forEach { (status, rung) ->
            assertEquals("$status", rung, connectionStepOf(status, CarPlayTransport.WIRELESS))
        }
        assertEquals(
            ConnectionStep.HOTSPOT,
            connectionStepOf(hotspotReady(), CarPlayTransport.WIRELESS),
        )
    }

    /** A rung for the other transport is not a rung here, and saying so is not the same as guessing. */
    @Test
    fun aStatusTheTransportNeverReportsHasNoRung() {
        assertNull(connectionStepOf(CarPlayStatus.StartingHotspot, CarPlayTransport.WIRED))
        assertNull(connectionStepOf(CarPlayStatus.DiscoveringIphone, CarPlayTransport.WIRELESS))
        assertNull(connectionStepOf(CarPlayStatus.OpeningDataPaths, CarPlayTransport.WIRELESS))
    }

    @Test
    fun aRunningSessionLeavesEveryRungDone() {
        listOf(
            CarPlayStatus.RunningControl,
            CarPlayStatus.WirelessActive,
            CarPlayStatus.ControlEnded,
        ).forEach { status ->
            val progress = connectionProgress(status, transportFor(status))
            assertEquals("$status", progress.ladder.size, progress.index)
            assertFalse("$status", progress.failed)
            assertTrue("$status", progress.rows.all { it.state == ConnectionStepState.DONE })
        }
    }

    @Test
    fun anAttemptInFlightLightsExactlyOneRungAndLeavesTheRestPending() {
        val progress = connectionProgress(CarPlayStatus.AttachingNetwork, CarPlayTransport.WIRELESS)
        val states = progress.rows.map { it.state }
        assertEquals(1, states.count { it == ConnectionStepState.ACTIVE })
        assertEquals(
            ConnectionStepState.ACTIVE,
            progress.rows[progress.ladder.indexOf(ConnectionStep.NETWORK)].state,
        )
        assertEquals(
            ConnectionStepState.PENDING,
            progress.rows[progress.ladder.indexOf(ConnectionStep.DATA_LINK)].state,
        )
        assertEquals(
            ConnectionStepState.DONE,
            progress.rows[progress.ladder.indexOf(ConnectionStep.PAIRING)].state,
        )
    }

    /** The failure is reported on the rung it stopped at, not on the last rung of the ladder. */
    @Test
    fun aFailureMarksTheRungItStoppedOn() {
        val progress = connectionProgress(
            status = CarPlayStatus.Failed(CarPlayFailureReason.BRING_UP_FAILED, "hotspot refused"),
            transport = CarPlayTransport.WIRELESS,
            failedAt = ConnectionStep.HOTSPOT,
        )
        assertTrue(progress.failed)
        assertEquals(
            ConnectionStepState.FAILED,
            progress.rows[progress.ladder.indexOf(ConnectionStep.HOTSPOT)].state,
        )
        assertEquals(
            ConnectionStepState.PENDING,
            progress.rows[progress.ladder.indexOf(ConnectionStep.SESSION)].state,
        )
    }

    /**
     * A failure raised outside any named rung still marks one. A ladder with nothing lit reads as
     * "nothing happened", which is the one thing a failure is not.
     */
    @Test
    fun aFailureWithNoRungRememberedStillMarksOne() {
        val progress = connectionProgress(
            status = CarPlayStatus.Failed(CarPlayFailureReason.BRING_UP_FAILED, "boom"),
            transport = CarPlayTransport.WIRED,
        )
        assertEquals(0, progress.index)
        assertTrue(progress.failed)
        assertEquals(ConnectionStepState.FAILED, progress.rows.first().state)
    }

    private fun hotspotReady() = CarPlayStatus.HotspotReady(
        ssid = "AndroidAP",
        band = "2.4 GHz",
        channel = 6,
        bssid = "02:00:00:00:00:00",
        address = "192.168.43.1",
        backend = "test",
    )

    private fun transportFor(status: CarPlayStatus) =
        if (status == CarPlayStatus.WirelessActive) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED
}
