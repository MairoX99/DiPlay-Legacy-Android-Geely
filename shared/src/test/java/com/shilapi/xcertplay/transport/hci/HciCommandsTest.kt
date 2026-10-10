package com.shilapi.xcertplay.transport.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HciCommandsTest {
    @Test fun localNameIsPaddedToTwoHundredFortyEightBytes() {
        val packet = HciCommands.writeLocalName("Xingrui CarPlay")

        assertEquals(0x13, packet[0].toInt() and 0xFF)
        assertEquals(0x0C, packet[1].toInt() and 0xFF)
        assertEquals(248, packet[2].toInt() and 0xFF)
        assertEquals(3 + 248, packet.size)
        assertEquals('X', packet[3].toChar())
        assertEquals(0, packet[3 + 248 - 1].toInt())
    }

    @Test fun extendedInquiryResponseCarriesBothServiceUuidsThenTheName() {
        val packet = HciCommands.writeExtendedInquiryResponse("Xingrui CarPlay", transmitPower = 4)
        val name = "Xingrui CarPlay"

        assertEquals(0x52, packet[0].toInt() and 0xFF)
        // Core Spec Vol 2 Part E 7.3.56: one FEC_Required octet, then 240 octets of EIR. A 240-octet
        // parameter is rejected, so inquiry scan never starts and the phone never sees the name.
        assertEquals(241, packet[2].toInt() and 0xFF)
        assertEquals(244, packet.size)
        assertEquals(0, packet[3].toInt() and 0xFF)
        // One Complete List of 128-bit UUIDs holding both: the CarPlay UUID that makes the iPhone offer
        // wireless CarPlay, then the iAP2 UUID that makes it expose the service SDP is asked about next.
        assertEquals(33, packet[4].toInt() and 0xFF)
        assertEquals(0x07, packet[5].toInt() and 0xFF)
        assertArrayEquals(ActionsBluetooth.CARPLAY_EIR_UUID_128, packet.copyOfRange(6, 22))
        assertArrayEquals(ActionsBluetooth.IAP2_EIR_UUID_128, packet.copyOfRange(22, 38))
        // Then the TX Power Level, then the name — the order the spec's structure list is read in.
        assertEquals(0x02, packet[38].toInt() and 0xFF)
        assertEquals(0x0A, packet[39].toInt() and 0xFF)
        assertEquals(4, packet[40].toInt() and 0xFF)
        assertEquals(name.length + 1, packet[41].toInt() and 0xFF)
        assertEquals(0x09, packet[42].toInt() and 0xFF)
        assertEquals(name, String(packet, 43, name.length))
        assertEquals(0, packet[packet.size - 1].toInt())
    }

    /**
     * Apple's Bluetooth Accessories spec §18.1.5 makes the TX Power Level one of the three things every
     * Apple-compatible accessory must put in its EIR. The octet is signed dBm — a radio reporting -4 sends
     * 0xFC — and the structure goes between the UUID list and the name, not after them.
     */
    @Test fun theEirCarriesTheTransmitPowerAsASignedOctet() {
        val packet = HciCommands.writeExtendedInquiryResponse("X", transmitPower = -4)

        assertArrayEquals(byteArrayOf(0x02, 0x0A, 0xFC.toByte()), packet.copyOfRange(38, 41))
        // The name still follows, one byte shorter for the one-character name.
        assertArrayEquals(byteArrayOf(0x02, 0x09, 'X'.code.toByte()), packet.copyOfRange(41, 44))
    }

    /**
     * The iAP2 UUID in the EIR is the accessory's, spelled the way Apple's Bluetooth Accessories spec spells
     * it: `0xFFCACADEAFDECADEDEFACADE00000000`, octet for octet, which is little-endian. It is not the UUID
     * the SDP query searches with — that one names the phone's side and ends 0xFE — so one cannot stand in
     * for the other.
     */
    @Test fun theIap2EirUuidIsTheAccessorySpellingInLittleEndian() {
        val spec = "FFCACADEAFDECADEDEFACADE00000000"
        val expected = ByteArray(16) { index -> spec.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

        assertArrayEquals(expected, ActionsBluetooth.IAP2_EIR_UUID_128)
        assertEquals(0xFE, SdpCodec.uuid128(ActionsBluetooth.IAP2_UUID_128)[15].toInt() and 0xFF)
    }

    @Test fun createConnectionParametersAreThirteenOctets() {
        val packet = HciCommands.createConnection("F4:4E:FC:F8:8B:36", allowRoleSwitch = 0x01)

        assertEquals(0x05, packet[0].toInt() and 0xFF)
        assertEquals(0x04, packet[1].toInt() and 0xFF)
        assertEquals(13, packet[2].toInt() and 0xFF)
        assertEquals(16, packet.size)
        assertArrayEquals(
            byteArrayOf(0x18, 0xCC.toByte(), 0x01, 0x00, 0x00, 0x00, 0x01),
            packet.copyOfRange(9, 16),
        )
    }

    @Test fun addressIsLittleEndianLowByteFirst() {
        assertArrayEquals(
            byteArrayOf(0x36, 0x8B.toByte(), 0xF8.toByte(), 0xFC.toByte(), 0x4E, 0xF4.toByte()),
            HciCommands.formatAddress("F4:4E:FC:F8:8B:36"),
        )
        assertEquals(
            "F4:4E:FC:F8:8B:36",
            HciCommands.parseAddress(
                byteArrayOf(0x36, 0x8B.toByte(), 0xF8.toByte(), 0xFC.toByte(), 0x4E, 0xF4.toByte()),
            ),
        )
    }

    @Test fun scanEnableAndSimplePairingModeAreOneByteParameters() {
        assertArrayEquals(
            byteArrayOf(0x1A, 0x0C, 0x01, 0x03),
            HciCommands.writeScanEnable(ActionsBluetooth.SCAN_ENABLE_DISCOVERABLE_CONNECTABLE),
        )
        assertArrayEquals(byteArrayOf(0x56, 0x0C, 0x01, 0x01), HciCommands.writeSimplePairingMode(true))
    }

    @Test fun ioCapabilityReplyIsNoInputNoOutput() {
        val packet = HciCommands.ioCapabilityRequestReply(
            address = "F4:4E:FC:F8:8B:36",
            ioCapability = HciCommands.IO_CAPABILITY_NO_INPUT_NO_OUTPUT,
            oobDataPresent = 0,
            authenticationRequirements = HciCommands.AUTHENTICATION_REQUIREMENTS_DEDICATED_BONDING,
        )

        assertEquals(0x2B, packet[0].toInt() and 0xFF)
        assertEquals(0x04, packet[1].toInt() and 0xFF)
        assertEquals(9, packet[2].toInt() and 0xFF)
        assertEquals(0x03, packet[3 + 6].toInt() and 0xFF)
        assertEquals(0x02, packet[3 + 8].toInt() and 0xFF)
    }

    /**
     * The three Secure Simple Pairing replies. Each answers the event whose code is its own OCF, and all of them
     * are Link Control (OGF 0x01), so the opcode is 0x0400 | event code. Transcribing the low octet as the whole
     * opcode produced 0x041F, 0x0420 and 0x0421 — Read_Clock_Offset and Read_LMP_Handle — which the controller
     * rejects, leaving the pairing request unanswered and the phone on its spinner.
     */
    @Test fun simplePairingRepliesAreLinkControlCommands() {
        val address = "F4:4E:FC:F8:8B:36"
        val replies = listOf(
            0x042B to HciCommands.ioCapabilityRequestReply(
                address,
                HciCommands.IO_CAPABILITY_NO_INPUT_NO_OUTPUT,
                0,
                HciCommands.AUTHENTICATION_REQUIREMENTS_DEDICATED_BONDING,
            ),
            0x042C to HciCommands.userConfirmationRequestReply(address),
            0x042D to HciCommands.userConfirmationRequestNegativeReply(address),
        )

        replies.forEach { (opcode, packet) ->
            assertEquals("low octet of 0x%04x".format(opcode), opcode and 0xFF, packet[0].toInt() and 0xFF)
            assertEquals("high octet of 0x%04x".format(opcode), (opcode shr 8) and 0xFF, packet[1].toInt() and 0xFF)
            assertEquals("OGF of 0x%04x".format(opcode), 0x01, (opcode shr 10) and 0x3F)
        }
        assertEquals(6, replies[1].second[2].toInt() and 0xFF)
        assertEquals(6, replies[2].second[2].toInt() and 0xFF)
    }
}
