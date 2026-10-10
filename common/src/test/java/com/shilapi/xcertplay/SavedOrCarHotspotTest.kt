package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
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
 * The path the home screen and the connection page both read. It asks the head unit, so it is worth
 * pinning that a car hotspot DiPlay was never told about still comes back with a name — that is the
 * question whose absence made the home screen name a hotspot it had no name for.
 *
 * The shadow answers the hidden AP-configuration call the way a head unit does, quotes included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SavedOrCarHotspotTest.ApRadio::class])
class SavedOrCarHotspotTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun seedCarHotspot(ssid: String, key: String?) {
        val radio = shadowOf(context.getSystemService(WifiManager::class.java)) as ApRadio
        radio.configured = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = key?.let { "\"$it\"" }
        }
    }

    @Test fun theCarsOwnHotspotFillsAPairTheDriverNeverSaved() {
        seedCarHotspot("CarNet", "carkey123")
        assertEquals("CarNet" to "carkey123", savedOrCarHotspot(context))
    }

    @Test fun savedCredentialsStandWhenTheCarNamesItsHotspotDifferently() {
        AirPlayPersistence.saveManualHotspotSsid(context, "DriverNet")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "driverkey")
        seedCarHotspot("CarNet", "carkey123")
        assertEquals("DriverNet" to "driverkey", savedOrCarHotspot(context))
    }

    @Test fun withNothingOnEitherSideThePairStaysEmpty() {
        assertEquals("" to "", savedOrCarHotspot(context))
    }

    @Implements(WifiManager::class)
    class ApRadio : ShadowWifiManager() {
        var configured: WifiConfiguration? = null
        @Implementation override fun getWifiApConfiguration(): WifiConfiguration? = configured
    }
}
