package com.shilapi.xcertplay.transport.hci

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.shilapi.xcertplay.DiagLog
import java.io.IOException

/**
 * The only place Android's USB API meets this stack.
 *
 * Commands go out on the default control pipe as a vendor request (`bmRequestType` 0x20, falling back to 0x21
 * with the interface number), which is where the Bluetooth spec puts them and what the probe found this adapter
 * needs. A radio that does not answer there stalls the transfer, so the bulk OUT pair is tried behind it and
 * remembered if it is the one that works. Events come back from the interrupt endpoint and ACL data uses the
 * bulk pair. Every call is bounded, so an unplugged adapter surfaces as a timeout rather than a thread parked in
 * the kernel.
 */
class UsbHciTransport(
    private val connection: UsbDeviceConnection,
    private val interfaces: List<UsbInterface>,
    private val eventIn: UsbEndpoint,
    private val aclIn: UsbEndpoint,
    private val aclOut: UsbEndpoint,
    private val commandOut: UsbEndpoint,
    preferredCommandPath: UsbHciCommandPath = UsbHciCommandPath.CONTROL_REQUEST,
) : HciTransport {
    private val commandLock = Any()
    private val eventLock = Any()
    private val aclReadLock = Any()
    private val aclWriteLock = Any()

    private val preferredPath = preferredCommandPath
    private var workingPath: UsbHciCommandPath? = null
    private var requestType = VENDOR_DEVICE_OUT
    private var requestIndex = 0

    override fun sendCommand(command: ByteArray) {
        synchronized(commandLock) {
            var lastResult = -1
            for (path in UsbCommandPaths.order(preferredPath, workingPath)) {
                val sent = when (path) {
                    UsbHciCommandPath.CONTROL_REQUEST -> controlCommand(command)
                    UsbHciCommandPath.BULK_OUT -> connection.bulkTransfer(
                        commandOut,
                        command,
                        command.size,
                        COMMAND_TIMEOUT_MILLIS,
                    )
                }
                if (sent >= 0) {
                    workingPath = path
                    return
                }
                lastResult = sent
            }
            throw IOException("no working path for an HCI command on this radio (last transfer $lastResult)")
        }
    }

    override fun readEvent(timeoutMillis: Long): ByteArray? {
        synchronized(eventLock) {
            // Not the endpoint's maxPacketSize: an event such as Link_Key_Notification is longer than a small
            // interrupt endpoint's packet, and a short read here would truncate the event itself.
            val buffer = ByteArray(EVENT_READ_BYTES)
            val count = connection.bulkTransfer(eventIn, buffer, buffer.size, timeoutMillis.toInt())
            if (count <= 0) return null
            return buffer.copyOf(count)
        }
    }

    override fun readAcl(timeoutMillis: Long): ByteArray? {
        synchronized(aclReadLock) {
            val buffer = ByteArray(ACL_READ_BYTES)
            val count = connection.bulkTransfer(aclIn, buffer, buffer.size, timeoutMillis.toInt())
            if (count <= 0) return null
            return buffer.copyOf(count)
        }
    }

    override fun writeAcl(packet: ByteArray) {
        synchronized(aclWriteLock) {
            val sent = connection.bulkTransfer(aclOut, packet, packet.size, ACL_WRITE_TIMEOUT_MILLIS)
            if (sent != packet.size) {
                // A stalled endpoint stays stalled: Android answers a timeout, a NAK and a latched halt with the
                // same negative result, and every later transfer on that endpoint fails the same way until the
                // halt is cleared. Nothing here did, so one bad ACL write took every answer after it — the phone
                // kept asking into a radio that had stopped talking, and the run ended with no line naming it.
                // The CH341 and NCM hosts recover the same way for the same reason; harmless when it was a NAK.
                clearEndpointHalt()
                throw IOException("USB ACL write transferred $sent of ${packet.size} bytes")
            }
        }
    }

    /**
     * Releases the ACL OUT endpoint a failed transfer may have left halted.
     *
     * Re-opening the device clears a halt too, which is why a full stack rebuild always recovered: one stalled
     * transfer used to consume the rest of the run until something rebuilt the link.
     */
    private fun clearEndpointHalt() {
        val result = try {
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_ENDPOINT,
                USB_REQUEST_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                aclOut.address,
                null,
                0,
                RECOVERY_TIMEOUT_MILLIS,
            )
        } catch (error: RuntimeException) {
            DiagLog.w(TAG, "halt clear on ACL OUT threw after a failed write", error)
            -1
        }
        DiagLog.w(TAG, "ACL write failed; halt clear on endpoint ${aclOut.address} returned $result")
    }

    override fun close() {
        interfaces.forEach { claimed -> runCatching { connection.releaseInterface(claimed) } }
        runCatching { connection.close() }
    }

    private fun controlCommand(command: ByteArray): Int {
        var sent = transfer(command, requestType, requestIndex)
        if (sent < 0 && requestType != VENDOR_CLASS_OUT) {
            requestType = VENDOR_CLASS_OUT
            requestIndex = interfaces.firstOrNull()?.id ?: 0
            sent = transfer(command, requestType, requestIndex)
        }
        return sent
    }

    private fun transfer(command: ByteArray, type: Int, index: Int): Int = connection.controlTransfer(
        type,
        0,
        0,
        index,
        command,
        command.size,
        COMMAND_TIMEOUT_MILLIS,
    )

    private companion object {
        const val TAG = "xcertplay-usb-hci"
        const val VENDOR_DEVICE_OUT = 0x20
        const val VENDOR_CLASS_OUT = 0x21
        const val COMMAND_TIMEOUT_MILLIS = 1_000
        const val ACL_WRITE_TIMEOUT_MILLIS = 1_000
        const val ACL_READ_BYTES = 4_096
        const val EVENT_READ_BYTES = 4_096

        /** Milliseconds allowed for the halt-clear control transfer before the endpoint is given up on. */
        const val RECOVERY_TIMEOUT_MILLIS = 200

        // USB 2.0 standard request: CLEAR_FEATURE(ENDPOINT_HALT) on one endpoint. Android exposes the
        // USB_DIR_* and USB_TYPE_* constants but not the USB_RECIP_* recipient codes, so it is spelled out.
        const val USB_REQUEST_CLEAR_FEATURE = 0x01
        const val USB_FEATURE_ENDPOINT_HALT = 0x0000
        const val USB_RECIP_ENDPOINT = 0x02
    }
}
