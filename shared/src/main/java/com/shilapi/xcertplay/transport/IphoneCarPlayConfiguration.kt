package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Build
import com.shilapi.xcertplay.DiagLog

data class CarPlayUsbConfiguration(
    val id: Int,
    val interfaces: List<UsbInterface>,
    internal val platformConfiguration: Any? = null,
)

/**
 * Descriptor-based discovery of the iPhone's CarPlay configuration.
 *
 * Configuration ids differ between iPhone models, so the configuration is identified by its
 * interfaces: Apple USB Multiplexor (USBMUX) plus the NCM/Ethernet function CarPlay uses.
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    fun find(device: UsbDevice): CarPlayUsbConfiguration? {
        val configurations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            platformConfigurations(device)
        } else {
            listOf(CarPlayUsbConfiguration(1, (0 until device.interfaceCount).map(device::getInterface)))
        }
        val chosen = configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) && hasAppleEthernet(it) }
            ?: configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) }
        DiagLog.i(
            TAG,
            "carplay config chosen=${chosen?.id} " +
                "available=${configurations.map { it.id }} detail=${chosen?.let(::describe)}",
        )
        return chosen
    }

    fun describe(configuration: CarPlayUsbConfiguration): String =
        configuration.interfaces.joinToString(",") { usbInterface ->
            "${usbInterface.id}/${alternateSetting(usbInterface)}" +
                ":${usbInterface.interfaceClass.toString(16)}" +
                ".${usbInterface.interfaceSubclass.toString(16)}" +
                ".${usbInterface.interfaceProtocol.toString(16)}" +
                "x${usbInterface.endpointCount}"
        }

    fun usbMuxInterface(configuration: CarPlayUsbConfiguration): UsbInterface? =
        configuration.interfaces.firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }

    private fun hasCdcNcm(configuration: CarPlayUsbConfiguration): Boolean =
        configuration.interfaces.any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    private fun hasAppleEthernet(configuration: CarPlayUsbConfiguration): Boolean =
        configuration.interfaces.any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }

    fun alternateSetting(usbInterface: UsbInterface): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) usbInterface.alternateSetting
        else if (usbInterface.interfaceClass == 0x0a && usbInterface.endpointCount > 0) 1 else 0

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun platformConfigurations(device: UsbDevice): List<CarPlayUsbConfiguration> =
        (0 until device.configurationCount).map { index ->
            val configuration: UsbConfiguration = device.getConfiguration(index)
            CarPlayUsbConfiguration(
                id = configuration.id,
                interfaces = (0 until configuration.interfaceCount).map(configuration::getInterface),
                platformConfiguration = configuration,
            )
        }

}
