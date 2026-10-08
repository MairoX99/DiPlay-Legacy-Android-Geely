package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal fun queueUsbRequest(request: UsbRequest, buffer: ByteBuffer): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) request.queue(buffer)
    else {
        @Suppress("DEPRECATION")
        request.queue(buffer, buffer.remaining())
    }

internal fun waitForUsbRequest(connection: UsbDeviceConnection, timeoutMillis: Long): UsbRequest? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) connection.requestWait(timeoutMillis.coerceAtLeast(1))
    else connection.requestWait()

/**
 * Waits for [request] to complete, giving up after [timeoutMillis].
 *
 * The timed `requestWait` overload arrived with API 26. Below it the only overload blocks until the
 * request completes or the connection closes, so a caller's deadline would otherwise be ignored and
 * the wait could never end on a silent phone. On those releases the deadline is enforced here by
 * cancelling [request] from a timer, the same unblocking `Iap2UsbSession.close` already uses.
 *
 * A cancelled request is consumed by the wait that reports the timeout, so an elapsed deadline
 * throws [TimeoutException] with nothing left queued; [Iap2UsbSession] drains accordingly. [buffer]
 * must be the empty buffer [request] was queued with: below API 26 its position is what separates a
 * completed transfer from a cancelled one, because the cancel can lose the race against a transfer
 * that had already completed. Callers that keep a request queued across calls (the NCM read) must
 * keep using [waitForUsbRequest].
 */
internal fun awaitUsbRequest(
    connection: UsbDeviceConnection,
    request: UsbRequest,
    buffer: ByteBuffer,
    timeoutMillis: Long,
): UsbRequest? {
    val timeout = timeoutMillis.coerceAtLeast(1)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return connection.requestWait(timeout)
    val expired = AtomicBoolean(false)
    val timer = usbRequestDeadlines.schedule(
        {
            expired.set(true)
            request.cancel()
        },
        timeout,
        TimeUnit.MILLISECONDS,
    )
    try {
        val completed = connection.requestWait()
        // A packet that completed as the deadline fired was already consumed from the phone, and the
        // wired link runs with acknowledgements disabled, so discarding it here would tear the mux
        // down over a read that actually succeeded. Only an empty buffer is a timeout.
        if (expired.get() && buffer.position() == 0) {
            throw TimeoutException("Timed out waiting for a USB request")
        }
        return completed
    } finally {
        timer.cancel(false)
    }
}

private val usbRequestDeadlines: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "usb-request-deadline").apply { isDaemon = true }
    }

internal fun selectUsbConfiguration(
    connection: UsbDeviceConnection,
    configuration: CarPlayUsbConfiguration,
): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
    selectUsbConfiguration21(connection, configuration.platformConfiguration)
} else {
    connection.controlTransfer(
        UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD,
        9,
        configuration.id,
        0,
        null,
        0,
        1_000,
    ) >= 0
}

internal fun selectUsbInterface(connection: UsbDeviceConnection, usbInterface: UsbInterface): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) connection.setInterface(usbInterface)
    else connection.controlTransfer(
        UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 1,
        11,
        IphoneCarPlayConfiguration.alternateSetting(usbInterface),
        usbInterface.id,
        null,
        0,
        1_000,
    ) >= 0

/**
 * Clears a latched halt on [endpoint] after a failed bulk transfer.
 *
 * Android reports a stalled endpoint, a NAK and a timeout with the same failed result, and a USB
 * stall stays latched per endpoint: every later transfer on it fails identically until something
 * clears the halt. Re-opening the device clears it, which is why a full stack rebuild always
 * recovers, so clearing it here keeps a single stalled transfer from looking like a dead endpoint.
 * A control transfer is harmless when the endpoint was never halted.
 */
internal fun clearUsbEndpointHalt(
    connection: UsbDeviceConnection,
    endpoint: UsbEndpoint,
    timeoutMillis: Int = USB_HALT_CLEAR_TIMEOUT_MILLIS,
): Int = try {
    connection.controlTransfer(
        UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_ENDPOINT,
        USB_REQUEST_CLEAR_FEATURE,
        USB_FEATURE_ENDPOINT_HALT,
        endpoint.address,
        null,
        0,
        timeoutMillis,
    )
} catch (_: RuntimeException) {
    -1
}

private const val USB_RECIP_ENDPOINT = 0x02
private const val USB_REQUEST_CLEAR_FEATURE = 1
private const val USB_FEATURE_ENDPOINT_HALT = 0
private const val USB_HALT_CLEAR_TIMEOUT_MILLIS = 1_000

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
private fun selectUsbConfiguration21(connection: UsbDeviceConnection, value: Any?): Boolean =
    connection.setConfiguration(value as UsbConfiguration)
