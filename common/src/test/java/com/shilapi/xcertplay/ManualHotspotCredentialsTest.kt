package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotAccessPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualHotspotCredentialsTest {
    @Test fun aStoredSsidWinsOverTheCarsOwnHotspot() {
        assertEquals(
            "driver's hotspot" to "driver's key",
            manualHotspotCredentials(
                storedSsid = "driver's hotspot",
                storedPassphrase = "driver's key",
                carHotspot = CarHotspotAccessPoint("car hotspot", "car key"),
            ),
        )
    }

    @Test fun anUnconfiguredPairIsFilledFromTheCarsOwnHotspot() {
        assertEquals(
            "car hotspot" to "car key",
            manualHotspotCredentials(
                storedSsid = "",
                storedPassphrase = "",
                carHotspot = CarHotspotAccessPoint("car hotspot", "car key"),
            ),
        )
    }

    @Test fun anOpenCarHotspotFillsAnEmptyKeyRatherThanLeavingItNull() {
        assertEquals(
            "car hotspot" to "",
            manualHotspotCredentials(
                storedSsid = "   ",
                storedPassphrase = "",
                carHotspot = CarHotspotAccessPoint("car hotspot", null),
            ),
        )
    }

    @Test fun aBlankPairStaysBlankWhenTheFirmwareHidesItsHotspot() {
        assertEquals(
            "" to "",
            manualHotspotCredentials(
                storedSsid = "",
                storedPassphrase = "",
                carHotspot = null,
            ),
        )
    }
}
