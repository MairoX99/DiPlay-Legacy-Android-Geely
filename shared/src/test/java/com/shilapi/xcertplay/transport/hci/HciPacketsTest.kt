package com.shilapi.xcertplay.transport.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HciPacketsTest {
    @Test fun commandIsOpcodeLittleEndianThenLengthThenParameters() {
        val packet = HciPackets.command(0x0C03, ByteArray(0))
        assertArrayEquals(byteArrayOf(0x03, 0x0C, 0x00), packet)

        val withParams = HciPackets.command(0x0C24, byteArrayOf(0x20, 0x04, 0x20))
        assertArrayEquals(byteArrayOf(0x24, 0x0C, 0x03, 0x20, 0x04, 0x20), withParams)
    }

    @Test fun eventHeaderIsCodeThenParameterLength() {
        val event = byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)
        val parsed = HciPackets.event(event)
        assertEquals(0x0E, parsed?.code)
        assertArrayEquals(byteArrayOf(0x01, 0x03, 0x0C, 0x00), parsed?.parameters)
    }

    @Test fun anEventThatArrivedShortIsDroppedRatherThanParsed() {
        // A Link_Key_Notification is 25 bytes; an interrupt endpoint can hand back less than that. The declared
        // length says 10 parameters arrived when only one did.
        val truncated = byteArrayOf(0x18, 0x0A, 0x5B)

        assertEquals(null, HciPackets.event(truncated))
        assertEquals(null, HciPackets.event(byteArrayOf(0x18)))
    }

    @Test fun reassemblerDropsTheBufferInsteadOfGrowingWithoutBound() {
        // A desync (a length field that never matches) must not pile up on a head unit with ~26 MB free.
        val reassembler = HciAclReassembler()
        val claim = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte()) + ByteArray(1024) { 0x11 }

        repeat(600) { reassembler.feed(claim) }

        assertTrue(reassembler.pendingBytes <= 64 * 1024)
    }

    @Test fun reassemblerSplitsTwoAclPacketsFromOneBulkRead() {
        val first = byteArrayOf(0x01, 0x00, 0x03, 0x00, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val second = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0xDD.toByte(), 0xEE.toByte())
        val reassembler = HciAclReassembler()

        val packets = reassembler.feed(first + second)

        assertEquals(2, packets.size)
        assertEquals(0x01, packets[0].handle)
        assertEquals(0, packets[0].packetBoundary)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), packets[0].payload)
        assertArrayEquals(byteArrayOf(0xDD.toByte(), 0xEE.toByte()), packets[1].payload)
    }

    @Test fun reassemblerCarriesAPartialPacketAcrossReads() {
        val head = byteArrayOf(0x01, 0x00, 0x04, 0x00, 0xAA.toByte(), 0xBB.toByte())
        val tail = byteArrayOf(0xCC.toByte(), 0xDD.toByte(), 0x01, 0x00, 0x01, 0x00, 0xEE.toByte())
        val reassembler = HciAclReassembler()

        assertEquals(0, reassembler.feed(head).size)
        val packets = reassembler.feed(tail)

        assertEquals(2, packets.size)
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            packets[0].payload,
        )
        assertArrayEquals(byteArrayOf(0xEE.toByte()), packets[1].payload)
    }

    @Test fun handleExtractsLowTwelveBitsFlagsExtractTheRest() {
        // handle 0x0ABC with packet boundary 0b01 and broadcast 0b00
        val data = HciAclData(handle = 0x0ABC, packetBoundary = 1, broadcast = 0, payload = ByteArray(0))
        assertEquals(0x0ABC, HciPackets.aclHandle(data))
    }
}
