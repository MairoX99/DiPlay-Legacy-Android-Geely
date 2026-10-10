package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.ExternalBluetoothRoute

/** One USB interface, reduced to the facts that decide whether it can carry HCI. */
internal data class UsbHciInterface(
    val index: Int,
    val interfaceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val interruptIn: Int? = null,
    val bulkIn: Int? = null,
    val bulkOut: Int? = null,
)

/**
 * Which USB devices are Bluetooth radios, and how to talk to them — without a list of model numbers, because
 * this ships to people whose adapter nobody has seen.
 *
 * Two things give a radio away: the Bluetooth interface class it announces (the USB-IF way), or the endpoint
 * shape every HCI-over-USB radio has anyway — events on an interrupt IN, ACL on a bulk pair. The adapter in this
 * car is of the second kind: it answers vendor control requests, which is exactly why a class-only test would
 * miss it.
 */
internal object UsbBluetoothRadios {
    const val WIRELESS_CONTROLLER = 0xE0
    const val RF_CONTROLLER = 0x01
    const val BLUETOOTH_PROTOCOL = 0x01
    const val VENDOR_SPECIFIC = 0xFF

    /** DiPlay's own I2C bridge. Taking it for a radio would break the wired path. */
    const val CH341_VENDOR_ID = 0x1A86
    const val CH341_PRODUCT_ID = 0x5512

    /**
     * Apple: the iPhone on the wired path, which is a USB device in this same process. An Apple interface carries an
     * interrupt IN with a bulk pair — the very shape [hasHciShape] accepts — so it has to be excluded by name, the
     * same way the CH341 is. That is an inverse guard, not a list of the radios that are allowed.
     */
    const val IPHONE_VENDOR_ID = 0x05AC

    fun announcesBluetooth(face: UsbHciInterface): Boolean =
        face.interfaceClass == WIRELESS_CONTROLLER &&
            face.subclass == RF_CONTROLLER &&
            face.protocol == BLUETOOTH_PROTOCOL

    fun hasHciShape(face: UsbHciInterface): Boolean =
        face.interruptIn != null && face.bulkIn != null && face.bulkOut != null

    fun isCandidate(vendorId: Int, productId: Int, faces: List<UsbHciInterface>): Boolean {
        if (vendorId == IPHONE_VENDOR_ID) return false
        if (vendorId == CH341_VENDOR_ID && productId == CH341_PRODUCT_ID) return false
        return faces.isNotEmpty() && faces.any { announcesBluetooth(it) || hasHciShape(it) }
    }

    /** The interface that owns the event endpoint: the announced one if there is one, else the HCI-shaped one. */
    fun controlInterface(faces: List<UsbHciInterface>): UsbHciInterface? =
        faces.firstOrNull { announcesBluetooth(it) && it.interruptIn != null }
            ?: faces.firstOrNull { hasHciShape(it) }
            ?: faces.firstOrNull { announcesBluetooth(it) }

    /** Where ACL lives, which on a two-interface radio is not the command interface. */
    fun aclInterface(faces: List<UsbHciInterface>, control: UsbHciInterface): UsbHciInterface =
        faces.firstOrNull { it !== control && it.bulkIn != null && it.bulkOut != null }
            ?: control

    /** The radio to use, or null when the route is off or nothing on the bus looks like one. */
    fun find(context: Context): UsbDevice? {
        if (!ExternalBluetoothRoute.enabled) return null
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return manager.deviceList.values
            .filter { isCandidate(it.vendorId, it.productId, shapesOf(it)) }
            .minByOrNull { it.deviceName }
    }

    fun present(context: Context): Boolean = find(context) != null

    /** A one-line description for the handshake log, so a car session says which radio it picked. */
    fun describe(device: UsbDevice): String {
        val faces = shapesOf(device)
        val control = controlInterface(faces)
        return buildString {
            append("${device.deviceName} ")
            append("%04x:%04x".format(device.vendorId, device.productId))
            append(" ifaces=${faces.size}")
            if (control != null) {
                append(" control=#${control.index}")
                append(" class=0x%02x".format(control.interfaceClass))
            }
        }
    }

    fun shapesOf(device: UsbDevice): List<UsbHciInterface> =
        (0 until device.interfaceCount).map { shapeOf(device, it) }

    /** Everything the transport needs, resolved to real endpoints on the interfaces to claim. */
    internal class HciEndpoints(
        val interfaces: List<UsbInterface>,
        val eventIn: UsbEndpoint,
        val aclIn: UsbEndpoint,
        val aclOut: UsbEndpoint,
        val commandOut: UsbEndpoint,
    )

    /** Null when the device has no usable event endpoint, ACL pair and command endpoint. */
    fun endpoints(device: UsbDevice): HciEndpoints? {
        val faces = shapesOf(device)
        val controlFace = controlInterface(faces) ?: return null
        val aclFace = aclInterface(faces, controlFace)
        val control = device.getInterface(controlFace.index)
        val acl = if (aclFace.index == controlFace.index) control else device.getInterface(aclFace.index)
        val eventIn = controlFace.interruptIn?.let { endpointAt(control, it) } ?: return null
        val aclIn = aclFace.bulkIn?.let { endpointAt(acl, it) } ?: return null
        val aclOut = aclFace.bulkOut?.let { endpointAt(acl, it) } ?: return null
        // Commands go out on the control interface's own bulk OUT when it has one, which is where a radio that
        // announces the Bluetooth class expects them; a shared bulk pair is the fallback.
        val commandOut = controlFace.bulkOut?.let { endpointAt(control, it) } ?: aclOut
        return HciEndpoints(
            interfaces = if (acl === control) listOf(control) else listOf(control, acl),
            eventIn = eventIn,
            aclIn = aclIn,
            aclOut = aclOut,
            commandOut = commandOut,
        )
    }

    private fun endpointAt(usbInterface: UsbInterface, address: Int): UsbEndpoint? =
        (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull { it.address == address }

    private fun shapeOf(device: UsbDevice, index: Int): UsbHciInterface {
        val usbInterface = device.getInterface(index)
        var interruptIn: Int? = null
        var bulkIn: Int? = null
        var bulkOut: Int? = null
        for (endpointIndex in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            val address = endpoint.address
            val isInput = endpoint.direction == UsbConstants.USB_DIR_IN
            when {
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT && isInput -> interruptIn = address
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && isInput -> bulkIn = address
                endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && !isInput -> bulkOut = address
            }
        }
        return UsbHciInterface(
            index = index,
            interfaceClass = usbInterface.interfaceClass,
            subclass = usbInterface.interfaceSubclass,
            protocol = usbInterface.interfaceProtocol,
            interruptIn = interruptIn,
            bulkIn = bulkIn,
            bulkOut = bulkOut,
        )
    }
}
