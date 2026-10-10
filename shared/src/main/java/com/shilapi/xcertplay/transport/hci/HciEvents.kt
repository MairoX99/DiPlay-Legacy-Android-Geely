package com.shilapi.xcertplay.transport.hci

internal object HciEvents {
    const val CONNECTION_COMPLETE = 0x03
    const val CONNECTION_REQUEST = 0x04
    const val DISCONNECTION_COMPLETE = 0x05
    const val AUTHENTICATION_COMPLETE = 0x06
    const val REMOTE_NAME_REQUEST_COMPLETE = 0x07
    const val ENCRYPTION_CHANGE = 0x08
    const val COMMAND_COMPLETE = 0x0E
    const val COMMAND_STATUS = 0x0F
    const val NUMBER_OF_COMPLETED_PACKETS = 0x13
    const val PIN_CODE_REQUEST = 0x16
    const val LINK_KEY_REQUEST = 0x17
    const val LINK_KEY_NOTIFICATION = 0x18

    // Secure Simple Pairing. These are the event codes from the spec; they are NOT the OCF of the command that
    // answers them, and the two sets are close enough to be transcribed one for the other. The reply to the
    // request below is 0x042B, while the request itself arrives as 0x31 — and 0x2B, 0x2D and 0x30 are not these
    // events at all: 0x2D is Synchronous_Connection_Changed and 0x30 is Encryption_Key_Refresh_Complete, so a
    // stack that waits on them answers nothing and the phone never leaves its pairing spinner.
    const val IO_CAPABILITY_REQUEST = 0x31
    const val IO_CAPABILITY_RESPONSE = 0x32
    const val USER_CONFIRMATION_REQUEST = 0x33
    const val SIMPLE_PAIRING_COMPLETE = 0x36

    /**
     * HCI error code 0x06, PIN or Key Missing: the other end has no link key matching ours.
     *
     * It is what both `Simple_Pairing_Complete` and `Authentication_Complete` report when a stored key has gone
     * stale on either side — the phone was reset, or the pairing was deleted on it. A link key is a shared
     * secret, so only dropping ours lets the next attempt pair from scratch; Linux marks the connection with
     * exactly this status and hands it to userspace to forget the key.
     */
    const val PIN_OR_KEY_MISSING = 0x06

    private fun word(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun address(bytes: ByteArray, offset: Int): String =
        HciCommands.parseAddress(bytes.copyOfRange(offset, offset + 6))

    fun commandCompleteOpcode(event: HciEventPacket): Int = word(event.parameters, 1)

    fun commandCompleteStatus(event: HciEventPacket): Int = event.parameters[3].toInt() and 0xFF

    fun commandCompleteReturnParameters(event: HciEventPacket): ByteArray =
        event.parameters.copyOfRange(3, event.parameters.size)

    // Status(1) + Num_HCI_Command_Packets(1) + Command_Opcode(2). Not the Command_Complete order: the
    // two events put Status and Num_HCI_Command_Packets opposite ways round, and swapping them lands the
    // opcode in a queue nothing waits on.
    fun commandStatus(event: HciEventPacket): Pair<Int, Int> =
        word(event.parameters, 2) to (event.parameters[0].toInt() and 0xFF)

    fun connectionComplete(event: HciEventPacket): ConnectionComplete = ConnectionComplete(
        status = event.parameters[0].toInt() and 0xFF,
        handle = word(event.parameters, 1),
        address = address(event.parameters, 3),
    )

    fun disconnectionComplete(event: HciEventPacket): DisconnectionComplete = DisconnectionComplete(
        status = event.parameters[0].toInt() and 0xFF,
        handle = word(event.parameters, 1),
        reason = event.parameters[3].toInt() and 0xFF,
    )

    /** [CONNECTION_REQUEST] carries the peer and its class of device; the link type follows and is not needed. */
    fun connectionRequest(event: HciEventPacket): String = address(event.parameters, 0)

    /** [AUTHENTICATION_COMPLETE] carries the outcome of the pairing's authentication, before any encryption. */
    fun authenticationComplete(event: HciEventPacket): AuthenticationComplete = AuthenticationComplete(
        status = event.parameters[0].toInt() and 0xFF,
        handle = word(event.parameters, 1),
    )

    fun encryptionChange(event: HciEventPacket): EncryptionChange = EncryptionChange(
        status = event.parameters[0].toInt() and 0xFF,
        handle = word(event.parameters, 1),
        enabled = (event.parameters[3].toInt() and 0xFF) != 0,
    )

    fun linkKeyRequest(event: HciEventPacket): String = address(event.parameters, 0)

    fun linkKeyNotification(event: HciEventPacket): LinkKeyNotification = LinkKeyNotification(
        address = address(event.parameters, 0),
        linkKey = event.parameters.copyOfRange(6, 22),
        keyType = event.parameters[22].toInt() and 0xFF,
    )

    fun ioCapabilityRequest(event: HciEventPacket): String = address(event.parameters, 0)

    fun userConfirmationRequest(event: HciEventPacket): String = address(event.parameters, 0)

    fun simplePairingComplete(event: HciEventPacket): Pair<Int, String> =
        (event.parameters[0].toInt() and 0xFF) to address(event.parameters, 1)

    fun pinCodeRequest(event: HciEventPacket): String = address(event.parameters, 0)

    fun numberOfCompletedPackets(event: HciEventPacket): List<Pair<Int, Int>> {
        val handles = event.parameters[0].toInt() and 0xFF
        return (0 until handles).map { index ->
            val base = 1 + index * 4
            word(event.parameters, base) to word(event.parameters, base + 2)
        }
    }

    // The three below take a Command_Complete's return parameters, which is what HciCommandResult.Complete carries.

    fun readBdAddrReturn(returnParameters: ByteArray): String = address(returnParameters, 1)

    fun readLocalVersionReturn(returnParameters: ByteArray): LocalVersion = LocalVersion(
        hciVersion = returnParameters[1].toInt() and 0xFF,
        lmpVersion = returnParameters[4].toInt() and 0xFF,
        manufacturer = word(returnParameters, 5),
    )

    fun readBufferSizeReturn(returnParameters: ByteArray): BufferSize = BufferSize(
        aclMtu = word(returnParameters, 1),
        // Status(1) + ACL length(2) + SCO length(1) + ACL count(2) + SCO count(2). The SCO length is one
        // octet where the ACL length is two, which is what moves the count off offset 3.
        aclMaxPackets = word(returnParameters, 4),
    )

    /**
     * `Read_Inquiry_Response_Transmit_Power_Level`'s return: status(1) then the power in dBm as one **signed**
     * octet, so a radio reporting -4 gets `0xFC` and not 252. Null when the adapter refused the command or
     * answered short, which the EIR then fills with a zero.
     */
    fun readInquiryResponseTransmitPowerReturn(returnParameters: ByteArray): Int? =
        if (returnParameters.size < 2 || returnParameters[0].toInt() != 0) {
            null
        } else {
            returnParameters[1].toInt()
        }
}

internal data class ConnectionComplete(val status: Int, val handle: Int, val address: String)

internal data class DisconnectionComplete(val status: Int, val handle: Int, val reason: Int)

internal data class AuthenticationComplete(val status: Int, val handle: Int)

internal data class EncryptionChange(val status: Int, val handle: Int, val enabled: Boolean)

internal data class LinkKeyNotification(val address: String, val linkKey: ByteArray, val keyType: Int)

internal data class LocalVersion(val hciVersion: Int, val lmpVersion: Int, val manufacturer: Int)

internal data class BufferSize(val aclMtu: Int, val aclMaxPackets: Int)
