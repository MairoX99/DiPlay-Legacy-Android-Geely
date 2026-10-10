package com.shilapi.xcertplay.transport.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class L2capCodecTest {
    @Test fun pduPutsLittleEndianLengthBeforeCid() {
        val pdu = L2capCodec.pdu(0x0040, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x40, 0x00, 0xAA.toByte(), 0xBB.toByte()),
            pdu,
        )
        assertEquals(0x0040, L2capCodec.pduCid(pdu))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), L2capCodec.pduPayload(pdu))
    }

    @Test fun signalingFrameCarriesCodeIdentifierAndLength() {
        val frame = L2capCodec.signaling(
            L2capCodec.CODE_CONNECTION_REQUEST,
            identifier = 1,
            data = byteArrayOf(0x03, 0x00, 0x40, 0x00),
        )
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0x04, 0x00, 0x03, 0x00, 0x40, 0x00),
            frame,
        )
        assertEquals(L2capCodec.CODE_CONNECTION_REQUEST, L2capCodec.signalingCode(frame))
        assertEquals(1, L2capCodec.signalingIdentifier(frame))
        assertArrayEquals(byteArrayOf(0x03, 0x00, 0x40, 0x00), L2capCodec.signalingData(frame))
    }

    @Test fun connectionRequestCarriesPsmThenSourceCid() {
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x40, 0x00),
            L2capCodec.connectionRequest(L2capCodec.PSM_RFCOMM, 0x0040),
        )
    }

    @Test fun connectionResponseCarriesDestinationSourceResultAndStatus() {
        assertArrayEquals(
            byteArrayOf(0x40, 0x00, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00),
            L2capCodec.connectionResponse(destinationCid = 0x0040, sourceCid = 0x0041, result = 0, status = 0),
        )
    }

    @Test fun mtuOptionIsTypeOneLengthTwo() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0xA0.toByte(), 0x02),
            L2capCodec.configurationOptionMtu(672),
        )
        val options = L2capCodec.configurationOptions(byteArrayOf(0x01, 0x02, 0xA0.toByte(), 0x02))
        assertEquals(1, options.size)
        assertEquals(0x01, options[0].first)
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x02), options[0].second)
    }

    /** A truncated option list must end the scan, not throw: the peer chooses what it sends. */
    @Test fun aTruncatedOptionListStopsTheScanInsteadOfThrowing() {
        val options = L2capCodec.configurationOptions(
            byteArrayOf(0x01, 0x02, 0xA0.toByte(), 0x02, 0x01, 0x04, 0x00),
        )
        assertEquals(1, options.size)
        assertEquals(0x01, options[0].first)
    }
}
