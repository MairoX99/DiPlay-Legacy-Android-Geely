package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DeviceConnectionSupportTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun ready(sdkInt: Int, usbHost: Boolean = true) = HeadUnitProbe(
        sdkInt = sdkInt,
        usbHost = usbHost,
        wifiManager = true,
        hotspotApi = true,
        bluetooth = BluetoothRead.ON,
        rfcommSocket = true,
    )

    @Test fun android5Through9CanUseUsbAndCarHotspotWhenTheCallsAnswer() {
        val report = DeviceConnectionSupport.assess(ready(22))
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    @Test fun android10AddsWifiDirect() {
        val report = DeviceConnectionSupport.assess(ready(Build.VERSION_CODES.Q))
        assertEquals(
            listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT, UsableConnection.WIFI_DIRECT),
            report.usable,
        )
        assertEquals(listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P), report.wirelessModes)
    }

    @Test fun bluetoothThatCannotBeCalledDropsTheHotspotAndSaysWhy() {
        val missing = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.MISSING))
        assertEquals(listOf(UsableConnection.USB), missing.usable)
        assertEquals(listOf(CarHotspotBlocker.NO_BLUETOOTH_ADAPTER), missing.carHotspotNotes)
        assertTrue(missing.wirelessModes.isEmpty())

        val failed = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.FAILED))
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_CALL_FAILED), failed.carHotspotNotes)
        assertTrue(failed.wirelessModes.isEmpty())

        val denied = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.DENIED))
        assertEquals(listOf(UsableConnection.USB), denied.usable)
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_PERMISSION), denied.carHotspotNotes)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), denied.wirelessModes)

        val noSocket = DeviceConnectionSupport.assess(ready(22).copy(rfcommSocket = false))
        assertEquals(listOf(CarHotspotBlocker.NO_RFCOMM), noSocket.carHotspotNotes)

        val noSwitch = DeviceConnectionSupport.assess(ready(22).copy(hotspotApi = false))
        assertEquals(listOf(CarHotspotBlocker.NO_HOTSPOT_API), noSwitch.carHotspotNotes)
        assertTrue(noSwitch.wirelessModes.isEmpty())
    }

    @Test fun bluetoothOffStaysOnTheSetupPageAndExplains() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(bluetooth = BluetoothRead.OFF))
        assertEquals(listOf(UsableConnection.USB), report.usable)
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_OFF), report.carHotspotNotes)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
    }

    @Test fun savedPlanOverridesTheLastTransport() {
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        val airplay = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        airplay.edit().clear().commit()
        AirPlayPersistence.saveWirelessEnabled(context, true)
        assertEquals(DefaultConnectionMode.LAST_USED, DiPlayPreferences.defaultConnectionMode(context))
        assertTrue(DiPlayPreferences.autoConnectWireless(context))

        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.USB)
        assertFalse(DiPlayPreferences.autoConnectWireless(context))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(context))

        DiPlayPreferences.saveDefaultConnectionMode(context, DefaultConnectionMode.WIRELESS)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertTrue(DiPlayPreferences.autoConnectWireless(context))

        prefs.edit().putString("default_connection_mode", "future_mode").commit()
        assertEquals(DefaultConnectionMode.LAST_USED, DiPlayPreferences.defaultConnectionMode(context))
        assertFalse(DiPlayPreferences.autoConnectWireless(context))
    }
}
