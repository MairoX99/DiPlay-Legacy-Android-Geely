package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * What the wired session does about a phone that refuses NCM bulk OUT while its CarPlay
 * authorization dialog waits for the user.
 *
 * The user answers on the phone's clock, so those failures are not evidence that bulk OUT is gone:
 * a latched halt, a NAK and this hold all arrive as the same negative transfer. These tests pin the
 * two apart — the authorization window tolerates the hold, and everything outside it keeps the
 * consecutive-failure policy that catches a real halt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    manifest = Config.NONE,
    shadows = [NcmAuthorizationWindowTest.Device::class, NcmAuthorizationWindowTest.Request::class],
)
class NcmAuthorizationWindowTest {
    private val frame = ByteArray(40) { (it + 1).toByte() }

    @Before fun reset() {
        Device.inbound = ByteArray(0)
        Device.writeResult = -1
        Device.request = null
        Request.queuedBuffer = null
    }

    private fun bridge(sessionLive: () -> Boolean, graceMillis: Long): NcmUsbBridge {
        val out = Shadow.newInstanceOf(UsbEndpoint::class.java)
        Device.outEndpoint = out
        return NcmUsbBridge(
            Shadow.newInstanceOf(UsbDeviceConnection::class.java),
            out,
            Shadow.newInstanceOf(UsbEndpoint::class.java),
            null,
            emptyList(),
            null,
            airPlaySessionLive = sessionLive,
            writeWindow = NcmWriteWindow(graceMillis),
        )
    }

    /** One inbound NTB16 block is what tells the write side the phone's data path is up. */
    private fun learnTheDataPathIsUp(bridge: NcmUsbBridge) {
        Device.inbound = Ntb16Codec.build(frame, 1)
        assertNotNull(bridge.recv(500))
    }

    @Test fun writesRefusedWhileTheDialogWaitsDoNotEndTheSession() {
        val bridge = bridge(sessionLive = { false }, graceMillis = 60_000L)
        learnTheDataPathIsUp(bridge)
        bridge.awaitCarPlayAuthorization()
        repeat(100) { bridge.send(frame, 20) }
    }

    @Test fun writesRefusedWithALiveSessionStillEndTheSessionAtTheLimit() {
        val bridge = bridge(sessionLive = { true }, graceMillis = 60_000L)
        learnTheDataPathIsUp(bridge)
        bridge.awaitCarPlayAuthorization()
        try {
            repeat(100) { bridge.send(frame, 20) }
            fail("Expected the consecutive failure limit to end the session")
        } catch (error: IphoneUsbException.DeviceUnavailable) {
            assertTrue(error.message.orEmpty().contains("while the data path was live"))
        }
    }

    @Test fun aDialogTheUserNeverAnswersEndsTheSessionOnceTheGracePasses() {
        val bridge = bridge(sessionLive = { false }, graceMillis = 0L)
        learnTheDataPathIsUp(bridge)
        bridge.awaitCarPlayAuthorization()
        try {
            bridge.send(frame, 20)
            fail("Expected the passed grace to end the session")
        } catch (error: IphoneUsbException.DeviceUnavailable) {
            assertTrue(error.message.orEmpty().contains("never authorized CarPlay"))
        }
    }

    @Test fun theSameRefusalsBeforeThePhoneIsAskedAreAFault() {
        val bridge = bridge(sessionLive = { false }, graceMillis = 60_000L)
        learnTheDataPathIsUp(bridge)
        try {
            repeat(100) { bridge.send(frame, 20) }
            fail("Expected the consecutive failure limit to end the session")
        } catch (error: IphoneUsbException.DeviceUnavailable) {
            assertTrue(error.message.orEmpty().contains("while the data path was live"))
        }
    }

    @Implements(UsbDeviceConnection::class)
    class Device {
        companion object {
            var outEndpoint: UsbEndpoint? = null

            /** What a bulk OUT transfer reports: negative is the hold/halt/NAK the phone sends. */
            var writeResult = -1
            var inbound = ByteArray(0)
            var request: UsbRequest? = null
        }

        @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int =
            if (endpoint === outEndpoint) writeResult else 0

        /** Completes the queued read with one inbound block, or times out like the platform does. */
        @Implementation fun requestWait(timeout: Long): UsbRequest? {
            val pending = request ?: throw TimeoutException("no queued request")
            val buffer = Request.queuedBuffer ?: throw TimeoutException("no queued buffer")
            if (inbound.isEmpty()) throw TimeoutException("no inbound frame")
            buffer.clear()
            buffer.put(inbound)
            inbound = ByteArray(0)
            return pending
        }
    }

    @Implements(UsbRequest::class)
    class Request {
        @RealObject lateinit var real: UsbRequest

        companion object {
            var queuedBuffer: ByteBuffer? = null
        }

        @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean = true

        @Implementation fun queue(buffer: ByteBuffer): Boolean {
            queuedBuffer = buffer
            Device.request = real
            return true
        }

        @Implementation fun cancel(): Boolean = true

        @Implementation fun close() = Unit
    }
}
