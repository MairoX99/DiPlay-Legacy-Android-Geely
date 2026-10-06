package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CarHotspotControllerTest {
    @Test
    fun leavesStationModeAloneWhenItIsAlreadyOff() {
        assertEquals(listOf(false), hotspotEnableAttempts(stationEnabled = false))
    }

    @Test
    fun triesAgainWithStationModeReleasedWhenItIsOn() {
        // Turning the driver's Wi-Fi off is a visible side effect, so it is the second attempt only.
        assertEquals(listOf(false, true), hotspotEnableAttempts(stationEnabled = true))
    }
}
