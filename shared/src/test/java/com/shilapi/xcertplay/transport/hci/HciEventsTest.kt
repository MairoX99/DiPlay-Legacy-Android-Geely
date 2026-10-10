package com.shilapi.xcertplay.transport.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HciEventsTest {
    @Test fun connectionCompleteReadsHandleAndAddress() {
        val event = HciEventPacket(
            0x03,
            byteArrayOf(
                0x00, // status
                0x0B, 0x00, // handle 0x000B
                0x5B, 0x2F, 0xD7.toByte(), 0x23, 0x60, 0xCC.toByte(), // phone bdaddr
                0x01, // ACL
                0x00,
            ),
        )

        val complete = HciEvents.connectionComplete(event)

        assertEquals(0, complete.status)
        assertEquals(0x0B, complete.handle)
        assertEquals("CC:60:23:D7:2F:5B", complete.address)
    }

    @Test fun authenticationCompleteReadsStatusAndHandle() {
        // Status(1) + Connection_Handle(2): authentication failure 0x05 on handle 0x000B.
        val event = HciEventPacket(0x06, byteArrayOf(0x05, 0x0B, 0x00))

        val complete = HciEvents.authenticationComplete(event)

        assertEquals(0x05, complete.status)
        assertEquals(0x0B, complete.handle)
    }

    @Test fun linkKeyNotificationCarriesSixteenByteKeyAndType() {
        val key = ByteArray(16) { it.toByte() }
        val event = HciEventPacket(
            0x18,
            HciCommands.formatAddress("CC:60:23:D7:2F:5B") + key + byteArrayOf(0x05),
        )

        val notification = HciEvents.linkKeyNotification(event)

        assertEquals("CC:60:23:D7:2F:5B", notification.address)
        assertArrayEquals(key, notification.linkKey)
        assertEquals(5, notification.keyType)
    }

    @Test fun commandCompleteReturnsOpcodeAndStatus() {
        val event = HciEventPacket(
            0x0E,
            byteArrayOf(0x01, 0x03, 0x0C, 0x00),
        )

        assertEquals(0x0C03, HciEvents.commandCompleteOpcode(event))
        assertEquals(0, HciEvents.commandCompleteStatus(event))
    }

    @Test fun commandStatusReturnsOpcodeAndStatus() {
        // Status 0x01, one packet still allowed, Create_Connection 0x0405.
        val event = HciEventPacket(0x0F, byteArrayOf(0x01, 0x01, 0x05, 0x04))
        assertEquals(0x0405 to 0x01, HciEvents.commandStatus(event))
    }

    @Test fun numberOfCompletedPacketsReadsEveryPair() {
        val event = HciEventPacket(
            0x13,
            byteArrayOf(0x02, 0x0B, 0x00, 0x03, 0x00, 0x0C, 0x00, 0x01, 0x00),
        )
        assertEquals(listOf(0x0B to 3, 0x0C to 1), HciEvents.numberOfCompletedPackets(event))
    }

    @Test fun readBdAddrReturnIsLittleEndianLowByteFirst() {
        val returnParameters = byteArrayOf(0x00) + HciCommands.formatAddress("F4:4E:FC:F8:8B:36")
        assertEquals("F4:4E:FC:F8:8B:36", HciEvents.readBdAddrReturn(returnParameters))
    }

    @Test fun readBufferSizeReturnReadsAclMtuAndPacketCount() {
        // Status 0, ACL length 0x00FB, SCO length 0x1E, ACL count 8, SCO count 0. Reading the count a
        // byte early would take the SCO length as its low half and report 2078 buffers.
        val returnParameters = byteArrayOf(0x00, 0xFB.toByte(), 0x00, 0x1E, 0x08, 0x00, 0x00, 0x00)
        val buffer = HciEvents.readBufferSizeReturn(returnParameters)
        assertEquals(0x00FB, buffer.aclMtu)
        assertEquals(8, buffer.aclMaxPackets)
    }

    /** The power is signed dBm, so 0xFC is -4 and not 252. An octet is the whole payload. */
    @Test fun readInquiryResponseTransmitPowerReturnIsSigned() {
        assertEquals(-4, HciEvents.readInquiryResponseTransmitPowerReturn(byteArrayOf(0x00, 0xFC.toByte())))
        assertEquals(20, HciEvents.readInquiryResponseTransmitPowerReturn(byteArrayOf(0x00, 0x14)))
    }

    /** A refusal or a short answer is null, which is what the EIR turns into a zero. */
    @Test fun readInquiryResponseTransmitPowerReturnIsNullWhenTheAdapterRefuses() {
        assertEquals(null, HciEvents.readInquiryResponseTransmitPowerReturn(byteArrayOf(0x01, 0x14)))
        assertEquals(null, HciEvents.readInquiryResponseTransmitPowerReturn(byteArrayOf(0x00)))
    }
}
