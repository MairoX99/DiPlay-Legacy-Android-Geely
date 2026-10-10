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

    @Test fun theAdapterSuppliesTheRfcommHopTheVendorStackCannot() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(rfcommSocket = false, adapterRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    @Test fun withoutEitherRfcommHopTheHotspotStaysUnavailable() {
        val report = DeviceConnectionSupport.assess(ready(22).copy(rfcommSocket = false))
        assertEquals(listOf(UsableConnection.USB), report.usable)
        assertTrue(report.wirelessModes.isEmpty())
        assertEquals(listOf(CarHotspotBlocker.NO_RFCOMM), report.carHotspotNotes)
    }

    @Test fun aHeadUnitWithNoBluetoothStackOffersTheHotspotThroughTheAdapter() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(bluetooth = BluetoothRead.MISSING, adapterRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        // No warning about the head unit's own stack: with the adapter supplying the hop, that is no longer a
        // reason this option cannot work, and DiPlay renders these notes under the list in warning colour.
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    @Test fun bluetoothOffWithTheAdapterStillOffersTheHotspot() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(bluetooth = BluetoothRead.OFF, adapterRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(CarHotspotBlocker.BLUETOOTH_OFF), report.carHotspotNotes)
    }

    @Test fun theAdapterCannotSubstituteForTheHotspotCalls() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(wifiManager = false, adapterRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB), report.usable)
        assertTrue(report.wirelessModes.isEmpty())
        assertEquals(listOf(CarHotspotBlocker.NO_WIFI), report.carHotspotNotes)
    }

    @Test fun aSuppliedHopCarriesTheRfcommLegTheVendorStackCannot() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(rfcommSocket = false, vendorHopRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertEquals(listOf(WirelessHotspotMode.MANUAL), report.wirelessModes)
        assertTrue(report.carHotspotNotes.isEmpty())
    }

    @Test fun aHeadUnitWithNoAospBluetoothOffersTheHotspotThroughASuppliedHop() {
        val report = DeviceConnectionSupport.assess(
            ready(22).copy(bluetooth = BluetoothRead.MISSING, vendorHopRfcomm = true),
        )
        assertEquals(listOf(UsableConnection.USB, UsableConnection.CAR_HOTSPOT), report.usable)
        assertTrue(report.carHotspotNotes.isEmpty())
    }
}
