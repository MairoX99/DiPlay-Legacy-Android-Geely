package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.UUID

/**
 * Connection methods this head unit can run.
 *
 * USB needs a USB host. The car hotspot also has to answer the calls the wireless path makes:
 * the hotspot switch, a Bluetooth adapter, and the RFCOMM socket used to reach the iPhone.
 * A radio that is merely off still counts as callable. Wi-Fi Direct is only offered from
 * Android 10. Local-only hotspot is not a saved choice.
 */
internal enum class UsableConnection { USB, CAR_HOTSPOT, WIFI_DIRECT }

internal enum class CarHotspotBlocker {
    NO_WIFI,
    NO_HOTSPOT_API,
    NO_BLUETOOTH_ADAPTER,
    BLUETOOTH_CALL_FAILED,
    BLUETOOTH_PERMISSION,
    BLUETOOTH_OFF,
    NO_RFCOMM,
}

/** What a Bluetooth state read actually returned. [DENIED] means the service is there but this app may not query it yet. */
internal enum class BluetoothRead { ON, OFF, MISSING, DENIED, FAILED }

internal data class HeadUnitProbe(
    val sdkInt: Int,
    val usbHost: Boolean,
    val wifiManager: Boolean,
    val hotspotApi: Boolean,
    val bluetooth: BluetoothRead,
    val rfcommSocket: Boolean,
)

internal data class ConnectionSupportReport(
    val usable: List<UsableConnection>,
    val carHotspotNotes: List<CarHotspotBlocker>,
    val wirelessModes: List<WirelessHotspotMode>,
)

internal object DeviceConnectionSupport {
    fun assess(probe: HeadUnitProbe): ConnectionSupportReport {
        val notes = mutableListOf<CarHotspotBlocker>()
        if (!probe.wifiManager) notes += CarHotspotBlocker.NO_WIFI
        else if (!probe.hotspotApi) notes += CarHotspotBlocker.NO_HOTSPOT_API
        val bluetoothCallable = when (probe.bluetooth) {
            BluetoothRead.MISSING -> {
                notes += CarHotspotBlocker.NO_BLUETOOTH_ADAPTER
                false
            }
            BluetoothRead.FAILED -> {
                notes += CarHotspotBlocker.BLUETOOTH_CALL_FAILED
                false
            }
            BluetoothRead.DENIED -> {
                notes += CarHotspotBlocker.BLUETOOTH_PERMISSION
                true
            }
            BluetoothRead.OFF, BluetoothRead.ON -> {
                if (!probe.rfcommSocket) notes += CarHotspotBlocker.NO_RFCOMM
                probe.rfcommSocket
            }
        }
        if (bluetoothCallable && probe.bluetooth == BluetoothRead.OFF) notes += CarHotspotBlocker.BLUETOOTH_OFF
        val callsAnswer = probe.wifiManager && probe.hotspotApi && bluetoothCallable
        val radioReady = callsAnswer && probe.bluetooth == BluetoothRead.ON
        val usable = buildList {
            if (probe.usbHost) add(UsableConnection.USB)
            if (radioReady) {
                add(UsableConnection.CAR_HOTSPOT)
                if (probe.sdkInt >= Build.VERSION_CODES.Q) add(UsableConnection.WIFI_DIRECT)
            }
        }
        val modes = buildList {
            if (!callsAnswer) return@buildList
            add(WirelessHotspotMode.MANUAL)
            if (probe.sdkInt >= Build.VERSION_CODES.Q) add(WirelessHotspotMode.WIFI_P2P)
        }
        return ConnectionSupportReport(usable, notes, modes)
    }

    fun inspect(context: Context): ConnectionSupportReport = assess(probe(context))

    @SuppressLint("MissingPermission")
    fun probe(context: Context): HeadUnitProbe {
        val wifi = runCatching { context.getSystemService(Context.WIFI_SERVICE) as? WifiManager }.getOrNull()
        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()
        val bluetooth = when (adapter) {
            null -> BluetoothRead.MISSING
            else -> runCatching { if (adapter.isEnabled) BluetoothRead.ON else BluetoothRead.OFF }
                .getOrElse { error -> if (error is SecurityException) BluetoothRead.DENIED else BluetoothRead.FAILED }
        }
        return HeadUnitProbe(
            sdkInt = Build.VERSION.SDK_INT,
            usbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            wifiManager = wifi != null,
            hotspotApi = wifi != null && hotspotSwitchExists(),
            bluetooth = bluetooth,
            rfcommSocket = rfcommSocketExists(),
        )
    }

    private fun hotspotSwitchExists(): Boolean = runCatching {
        WifiManager::class.java.getMethod(
            "setWifiApEnabled",
            WifiConfiguration::class.java,
            Boolean::class.javaPrimitiveType,
        )
    }.isSuccess

    private fun rfcommSocketExists(): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("createRfcommSocketToServiceRecord", UUID::class.java)
    }.isSuccess
}
