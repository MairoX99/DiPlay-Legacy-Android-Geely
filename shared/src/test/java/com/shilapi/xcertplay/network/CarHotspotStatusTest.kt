package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

/**
 * The firmware's half of the bargain.
 *
 * This read is a hidden API against a vendor build nobody has documented, and the whole clean-install
 * path now rests on it: what it answers becomes the hotspot name and key the driver is shown. So the
 * interesting cases are the ones where it answers badly. Every one of them has to come back as "no
 * hotspot" — a null the caller already handles — because the alternative is an exception out of the
 * first screen a freshly installed app draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [CarHotspotStatusTest.Radio::class])
class CarHotspotStatusTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(context.getSystemService(WifiManager::class.java)) as Radio

    @Test fun readsTheQuotedNameAndKey() {
        radio.answer = configuration(ssid = "\"GEELY-1234\"", key = "\"carkey123\"")
        assertEquals(CarHotspotAccessPoint("GEELY-1234", "carkey123"), CarHotspotStatus.accessPoint(context))
    }

    @Test fun anUnquotedNameIsLeftAlone() {
        radio.answer = configuration(ssid = "GEELY-1234", key = "carkey123")
        assertEquals(CarHotspotAccessPoint("GEELY-1234", "carkey123"), CarHotspotStatus.accessPoint(context))
    }

    @Test fun aFirmwareThatThrowsIsTreatedAsNoHotspot() {
        radio.failure = RuntimeException("vendor build refused getWifiApConfiguration")
        assertNull(CarHotspotStatus.accessPoint(context))
    }

    @Test fun aFirmwareThatAnswersWithNothingIsTreatedAsNoHotspot() {
        radio.answer = null
        assertNull(CarHotspotStatus.accessPoint(context))
    }

    @Test fun aHeadUnitWhoseHotspotWasNeverConfiguredHasNoName() {
        radio.answer = configuration(ssid = "", key = "")
        assertNull(CarHotspotStatus.accessPoint(context))
    }

    @Test fun anEmptyQuotedNameIsNoName() {
        radio.answer = configuration(ssid = "\"\"", key = "\"\"")
        assertNull(CarHotspotStatus.accessPoint(context))
    }

    @Test fun anOpenHotspotComesBackWithNoKey() {
        radio.answer = configuration(ssid = "\"GEELY-1234\"", key = null)
        assertEquals(CarHotspotAccessPoint("GEELY-1234", null), CarHotspotStatus.accessPoint(context))
    }

    /** A legacy AP configuration quotes both values; a driver's key may hold anything but quotes. */
    private fun configuration(ssid: String, key: String?): WifiConfiguration =
        WifiConfiguration().apply {
            SSID = ssid
            preSharedKey = key
        }

    @Implements(WifiManager::class)
    class Radio : ShadowWifiManager() {
        var answer: WifiConfiguration? = null
        var failure: Throwable? = null

        @Implementation
        override fun getWifiApConfiguration(): WifiConfiguration? {
            failure?.let { throw it }
            return answer
        }
    }
}
