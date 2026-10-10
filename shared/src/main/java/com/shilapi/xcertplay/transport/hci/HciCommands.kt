package com.shilapi.xcertplay.transport.hci

import java.util.Locale

/**
 * HCI command parameters, one function per command.
 *
 * Opcodes are transcribed from the Bluetooth Core Spec, Vol 2, Part E, 7. Typos here are invisible
 * until the adapter answers Command Status with a non-zero status, so the tests pin the whole
 * header of every packet this stack sends.
 */
internal object HciCommands {
    const val IO_CAPABILITY_NO_INPUT_NO_OUTPUT = 0x03

    /** Dedicated Bonding, MITM not required: a stored link key, with no demand Just Works cannot meet. */
    const val AUTHENTICATION_REQUIREMENTS_DEDICATED_BONDING = 0x02

    /** [acceptConnectionRequest] role: remain the slave, leaving the peer that paged us as master. */
    const val ROLE_REMAIN_SLAVE = 0x01

    private const val LOCAL_NAME_BYTES = 248
    private const val EIR_BYTES = 240
    private const val COMPLETE_LOCAL_NAME = 0x09
    private const val COMPLETE_LIST_OF_128_BIT_UUIDS = 0x07
    /** EIR data type 0x0A, TX Power Level: one signed octet in dBm. */
    private const val TX_POWER_LEVEL = 0x0A

    fun reset(): ByteArray = HciPackets.command(0x0C03, ByteArray(0))
    fun readBdAddr(): ByteArray = HciPackets.command(0x1009, ByteArray(0))
    fun readLocalVersion(): ByteArray = HciPackets.command(0x1001, ByteArray(0))
    fun readBufferSize(): ByteArray = HciPackets.command(0x1005, ByteArray(0))

    /**
     * Read_Inquiry_Response_Transmit_Power_Level, which is where the number for the EIR's TX Power Level
     * structure comes from. It has no parameters and no side effects, so it can be asked on every bring-up
     * rather than remembered across them.
     */
    fun readInquiryResponseTransmitPower(): ByteArray = HciPackets.command(0x0C58, ByteArray(0))

    fun writeLocalName(name: String): ByteArray {
        val padded = ByteArray(LOCAL_NAME_BYTES)
        val encoded = name.toByteArray(Charsets.UTF_8)
        require(encoded.size <= LOCAL_NAME_BYTES) { "local name must fit in $LOCAL_NAME_BYTES bytes" }
        encoded.copyInto(padded)
        return HciPackets.command(0x0C13, padded)
    }

    fun writeExtendedInquiryResponse(name: String, transmitPower: Int): ByteArray {
        val encoded = name.toByteArray(Charsets.UTF_8)
        // One Complete List of 128-bit UUIDs holding both, because a second structure of the same type
        // would have to be the incomplete list. The CarPlay UUID is what makes the iPhone offer wireless
        // CarPlay; the iAP2 UUID is what makes it expose the iAP2 service the pair then negotiates over.
        val uuids = ActionsBluetooth.CARPLAY_EIR_UUID_128 + ActionsBluetooth.IAP2_EIR_UUID_128
        // [length][type][data…]: the length octet counts the type octet, exactly as in a BLE advertisement.
        val uuidStructure = byteArrayOf(
            (uuids.size + 1).toByte(),
            COMPLETE_LIST_OF_128_BIT_UUIDS.toByte(),
        ) + uuids
        // Apple's Bluetooth Accessories spec §18.1.5 makes the TX Power Level one of the three things every
        // Apple-compatible accessory must put in its EIR, next to the local name and the iAP2 UUID. A radio
        // that will not report its power gets a zero written in its place: the structure is what the spec asks
        // for, and "not reported" is a truer reading of a zero than an absence the phone cannot distinguish
        // from a stack that never implemented this.
        val powerStructure = byteArrayOf(0x02, TX_POWER_LEVEL.toByte(), transmitPower.toByte())
        val nameStructure = byteArrayOf((encoded.size + 1).toByte(), COMPLETE_LOCAL_NAME.toByte()) + encoded
        val structure = uuidStructure + powerStructure + nameStructure
        require(structure.size <= EIR_BYTES) { "EIR data must fit in $EIR_BYTES bytes" }
        val payload = ByteArray(EIR_BYTES)
        structure.copyInto(payload)
        // FEC_Required is its own octet in front of the 240-octet EIR. Omitting it makes the adapter
        // reject the command, so the name is never made discoverable.
        return HciPackets.command(0x0C52, byteArrayOf(0) + payload)
    }

    fun writeClassOfDevice(classOfDevice: Int): ByteArray = HciPackets.command(
        0x0C24,
        byteArrayOf(
            (classOfDevice and 0xFF).toByte(),
            ((classOfDevice shr 8) and 0xFF).toByte(),
            ((classOfDevice shr 16) and 0xFF).toByte(),
        ),
    )

    fun writeScanEnable(value: Int): ByteArray =
        HciPackets.command(0x0C1A, byteArrayOf(value.toByte()))

    fun writeSimplePairingMode(enabled: Boolean): ByteArray =
        HciPackets.command(0x0C56, byteArrayOf(if (enabled) 1 else 0))

    fun writeConnectionAcceptTimeout(slots: Int): ByteArray = HciPackets.command(
        0x0C16,
        byteArrayOf((slots and 0xFF).toByte(), ((slots shr 8) and 0xFF).toByte()),
    )

    /**
     * Accepts a link the peer asked for. Only the host may answer a Connection_Request; a controller whose host
     * stays silent rejects the link when Connection_Accept_Timeout runs out, which looks to the phone like a
     * pairing that never starts.
     *
     * [role] asks for the master role (0x00) or to stay the slave (0x01). Staying the slave leaves the pager as
     * master, so no role switch runs in the middle of the pairing that follows.
     */
    fun acceptConnectionRequest(address: String, role: Int): ByteArray =
        HciPackets.command(0x0409, formatAddress(address) + byteArrayOf(role.toByte()))

    fun createConnection(address: String, allowRoleSwitch: Int): ByteArray =
        HciPackets.command(
            0x0405,
            // Thirteen octets: BD_ADDR, packet types 0xCC18 (DM1/DH1/DM3/DH3/DM5/DH5),
            // page-scan repetition R1, one reserved octet, clock offset, allow role switch.
            // A fourteenth octet makes the controller reject the command.
            formatAddress(address) +
                byteArrayOf(0x18, 0xCC.toByte(), 0x01, 0x00, 0x00, 0x00) +
                byteArrayOf(allowRoleSwitch.toByte()),
        )

    fun authenticationRequested(handle: Int): ByteArray =
        HciPackets.command(0x0411, byteArrayOf((handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte()))

    fun setConnectionEncryption(handle: Int, enable: Boolean): ByteArray = HciPackets.command(
        0x0413,
        byteArrayOf(
            (handle and 0xFF).toByte(),
            ((handle shr 8) and 0xFF).toByte(),
            if (enable) 1 else 0,
        ),
    )

    fun linkKeyRequestReply(address: String, linkKey: ByteArray): ByteArray {
        require(linkKey.size == 16) { "a link key is 16 bytes" }
        return HciPackets.command(0x040B, formatAddress(address) + linkKey)
    }

    fun linkKeyRequestNegativeReply(address: String): ByteArray =
        HciPackets.command(0x040C, formatAddress(address))

    // Secure Simple Pairing runs over Link Control (OGF 0x01), so these opcodes are 0x0400 | OCF. The OCF is the
    // same number as the event code of the request being answered, which is what makes them easy to mis-transcribe:
    // 0x041F and 0x0420 are Read_Clock_Offset and Read_LMP_Handle. The controller answers those with Invalid HCI
    // Command Parameters, the pairing request goes unanswered, and the phone sits on its pairing spinner.
    fun ioCapabilityRequestReply(
        address: String,
        ioCapability: Int,
        oobDataPresent: Int,
        authenticationRequirements: Int,
    ): ByteArray = HciPackets.command(
        0x042B,
        formatAddress(address) +
            byteArrayOf(ioCapability.toByte(), oobDataPresent.toByte(), authenticationRequirements.toByte()),
    )

    fun userConfirmationRequestReply(address: String): ByteArray =
        HciPackets.command(0x042C, formatAddress(address))

    fun userConfirmationRequestNegativeReply(address: String): ByteArray =
        HciPackets.command(0x042D, formatAddress(address))

    fun pinCodeRequestNegativeReply(address: String): ByteArray =
        HciPackets.command(0x040E, formatAddress(address))

    fun disconnect(handle: Int, reason: Int): ByteArray = HciPackets.command(
        0x0406,
        byteArrayOf(
            (handle and 0xFF).toByte(),
            ((handle shr 8) and 0xFF).toByte(),
            reason.toByte(),
        ),
    )

    fun formatAddress(address: String): ByteArray {
        val parts = address.split(':')
        require(parts.size == 6) { "address must have six octets: $address" }
        // HCI carries BD_ADDR least-significant octet first.
        return ByteArray(6) { index -> parts[5 - index].toInt(16).toByte() }
    }

    fun parseAddress(bytes: ByteArray): String {
        require(bytes.size >= 6) { "an address is six bytes" }
        return (5 downTo 0).joinToString(":") { index ->
            String.format(Locale.US, "%02X", bytes[index].toInt() and 0xFF)
        }
    }
}
