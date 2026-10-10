package com.shilapi.xcertplay.transport.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfcommFrameTest {
    @Test fun uihCarriesAnFcsOverTheAddressAndControlOnly() {
        val frame = RfcommFrame(
            dlci = 2, type = RfcommFrameType.UIH, command = true, pollFinal = false,
            credits = null, mccType = 0, information = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )

        val encoded = RfcommFrameCodec.encode(frame)

        // Length EA is the low bit: two information octets encode as 0x05, and the FCS is not counted.
        assertArrayEquals(
            byteArrayOf(0x0B, 0xEF.toByte(), 0x05, 0xAA.toByte(), 0xBB.toByte(), 0x9A.toByte()),
            encoded,
        )
        val decoded = RfcommFrameCodec.decode(encoded)!!
        assertEquals(RfcommFrameType.UIH, decoded.type)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), decoded.information)
    }

    @Test fun uihWithPollFinalCarriesACreditOctet() {
        val frame = RfcommFrame(
            dlci = 2, type = RfcommFrameType.UIH, command = true, pollFinal = true,
            credits = 7, mccType = 0, information = byteArrayOf(0x01),
        )

        val encoded = RfcommFrameCodec.encode(frame)
        // One data octet, so the length is 1 and encodes as 0x03. The credit octet sits between the length and
        // the data and is not counted.
        assertArrayEquals(
            byteArrayOf(0x0B, 0xFF.toByte(), 0x03, 0x07, 0x01, 0x86.toByte()),
            encoded,
        )
        val decoded = RfcommFrameCodec.decode(encoded)!!
        assertEquals(7, decoded.credits)
        assertArrayEquals(byteArrayOf(0x01), decoded.information)

        // The frame that only grants credits carries no data at all, so it declares a length of zero.
        val creditsOnly = RfcommFrameCodec.encode(
            RfcommFrame(
                dlci = 2, type = RfcommFrameType.UIH, command = true, pollFinal = true,
                credits = 7, mccType = 0, information = ByteArray(0),
            ),
        )
        assertArrayEquals(
            byteArrayOf(0x0B, 0xFF.toByte(), 0x01, 0x07, 0x86.toByte()),
            creditsOnly,
        )
        val decodedCreditsOnly = RfcommFrameCodec.decode(creditsOnly)!!
        assertEquals(7, decodedCreditsOnly.credits)
        assertArrayEquals(ByteArray(0), decodedCreditsOnly.information)
    }

    @Test fun sabmCarriesAnFcsAndDecodesBack() {
        val encoded = RfcommFrameCodec.encode(
            RfcommFrame(
                0, RfcommFrameType.SABM, command = true, pollFinal = true, credits = null,
                mccType = 0, information = ByteArray(0),
            ),
        )

        assertArrayEquals(byteArrayOf(0x03, 0x3F, 0x01, 0x1C), encoded)

        val decoded = RfcommFrameCodec.decode(encoded)!!
        assertEquals(RfcommFrameType.SABM, decoded.type)
        assertEquals(0, decoded.dlci)
        assertTrue(decoded.command)
        assertTrue(decoded.pollFinal)
    }

    @Test fun aCorruptedFcsIsRejected() {
        val encoded = RfcommFrameCodec.encode(
            RfcommFrame(
                0, RfcommFrameType.SABM, command = true, pollFinal = true, credits = null,
                mccType = 0, information = ByteArray(0),
            ),
        ).copyOf()
        encoded[encoded.size - 1] = (encoded[encoded.size - 1] + 1).toByte()

        assertNull(RfcommFrameCodec.decode(encoded))
    }

    @Test fun aPartialFrameReturnsNullInsteadOfThrowing() {
        assertNull(RfcommFrameCodec.decode(byteArrayOf(0x0B, 0xEF.toByte())))
    }

    @Test fun aLongInformationFieldUsesATwoByteLength() {
        val frame = RfcommFrame(
            dlci = 2, type = RfcommFrameType.UIH, command = true, pollFinal = false,
            credits = null, mccType = 0, information = ByteArray(200) { 0x5A },
        )

        val encoded = RfcommFrameCodec.encode(frame)

        // 200 is past one length octet. EA stays clear, and the low seven bits sit in the first octet.
        assertEquals(0x90, encoded[2].toInt() and 0xFF)
        assertEquals(0x01, encoded[3].toInt() and 0xFF)
        assertEquals(0x9A, encoded[encoded.size - 1].toInt() and 0xFF)
        val decoded = RfcommFrameCodec.decode(encoded)!!
        assertEquals(200, decoded.information.size)
        assertEquals(0x5A, decoded.information[199].toInt() and 0xFF)
    }

    /** Eight octets: raw DLCI, credit flow control, priority, one-byte timer, MTU, retransmits, credits. */
    @Test fun pnIsEightOctetsWithFrameSizeAndCredits() {
        val pn = RfcommFrameCodec.parameterNegotiation(
            dlci = 2,
            maxFrameSize = 672,
            initialCredits = 7,
            command = true,
        )

        assertArrayEquals(
            byteArrayOf(0x02, 0xF0.toByte(), 0x00, 0x00, 0xA0.toByte(), 0x02, 0x00, 0x07),
            pn,
        )
        // A response carries 0xE0 in the flow-control octet where the command carries 0xF0.
        assertArrayEquals(
            byteArrayOf(0x02, 0xE0.toByte(), 0x00, 0x00, 0xA0.toByte(), 0x02, 0x00, 0x07),
            RfcommFrameCodec.parameterNegotiation(
                dlci = 2,
                maxFrameSize = 672,
                initialCredits = 7,
                command = false,
            ),
        )
    }

    @Test fun parameterNegotiationIsAUihOnTheControlChannel() {
        val encoded = RfcommFrameCodec.encode(
            RfcommFrame(
                0, RfcommFrameType.MCC, command = true, pollFinal = true, credits = null,
                mccType = RfcommFrameCodec.MCC_PN,
                information = RfcommFrameCodec.parameterNegotiation(2, 672, 7, command = true),
            ),
        )

        assertEquals(0x03, encoded[0].toInt() and 0xFF)
        assertEquals(0xEF, encoded[1].toInt() and 0xFF)
        assertEquals(0x83, encoded[3].toInt() and 0xFF)
        assertEquals(0x11, encoded[4].toInt() and 0xFF)
        assertEquals(0x02, encoded[5].toInt() and 0xFF)
        assertEquals(0xF0, encoded[6].toInt() and 0xFF)
        assertEquals(7, encoded[12].toInt() and 0xFF)
        val decoded = RfcommFrameCodec.decode(encoded)!!
        assertEquals(RfcommFrameType.MCC, decoded.type)
        assertEquals(RfcommFrameCodec.MCC_PN, decoded.mccType)
        assertEquals(7, decoded.information[7].toInt() and 0xFF)
    }

    @Test fun mccFramesAreIdentifiedByTheirType() {
        val encoded = RfcommFrameCodec.encode(
            RfcommFrame(
                0, RfcommFrameType.MCC, command = true, pollFinal = true, credits = null,
                mccType = RfcommFrameCodec.MCC_PN, information = byteArrayOf(0x09),
            ),
        )
        val decoded = RfcommFrameCodec.decode(encoded)!!

        assertEquals(RfcommFrameType.MCC, decoded.type)
        assertEquals(RfcommFrameCodec.MCC_PN, decoded.mccType)
        assertArrayEquals(byteArrayOf(0x09), decoded.information)
    }
}
