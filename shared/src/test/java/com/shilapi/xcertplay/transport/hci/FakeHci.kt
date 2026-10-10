package com.shilapi.xcertplay.transport.hci

/** A Command_Complete event: Num_HCI_Command_Packets(1) + Command_Opcode(2) + Status(1) + returns. */
internal fun commandComplete(opcode: Int, status: Int, returns: ByteArray = ByteArray(0)): ByteArray =
    byteArrayOf(0x0E, (4 + returns.size).toByte(), 0x01) +
        byteArrayOf((opcode and 0xFF).toByte(), ((opcode shr 8) and 0xFF).toByte(), status.toByte()) +
        returns

/** A Command_Status event: Status(1) + Num_HCI_Command_Packets(1) + Command_Opcode(2). */
internal fun commandStatus(opcode: Int, status: Int): ByteArray =
    byteArrayOf(0x0F, 0x04, status.toByte(), 0x01) +
        byteArrayOf((opcode and 0xFF).toByte(), ((opcode shr 8) and 0xFF).toByte())

/** A Number_Of_Completed_Packets event: one (handle, count) pair. */
internal fun numberOfCompletedPackets(handle: Int, count: Int): ByteArray = byteArrayOf(
    0x13, 0x05, 0x01,
    (handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte(),
    (count and 0xFF).toByte(), ((count shr 8) and 0xFF).toByte(),
)

/** A Connection_Complete event for an ACL link on [handle]. */
internal fun connectionCompleteEvent(handle: Int, address: String): ByteArray = byteArrayOf(
    0x03, 0x0B, 0x00,
    (handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte(),
) + HciCommands.formatAddress(address) + byteArrayOf(0x01, 0x00)

/** An Encryption_Change event. */
internal fun encryptionChangeEvent(handle: Int, enabled: Boolean): ByteArray = byteArrayOf(
    0x08, 0x04, 0x00,
    (handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte(),
    if (enabled) 1 else 0,
)

/** An Authentication_Complete event: Status(1) + Connection_Handle(2). */
internal fun authenticationCompleteEvent(handle: Int, status: Int = 0): ByteArray = byteArrayOf(
    0x06, 0x03, status.toByte(),
    (handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte(),
)

/** A Link_Key_Notification the phone's pairing produced. */
internal fun linkKeyNotificationEvent(address: String, linkKey: ByteArray, keyType: Int): ByteArray =
    byteArrayOf(0x18, (6 + linkKey.size + 1).toByte()) + HciCommands.formatAddress(address) + linkKey +
        byteArrayOf(keyType.toByte())

/** An ACL packet carrying one L2CAP PDU on [cid]. */
internal fun aclFrame(handle: Int, cid: Int, payload: ByteArray): ByteArray {
    val pdu = L2capCodec.pdu(cid, payload)
    return byteArrayOf(
        (handle and 0xFF).toByte(),
        ((handle shr 8) and 0xFF).toByte(),
        (pdu.size and 0xFF).toByte(),
        ((pdu.size shr 8) and 0xFF).toByte(),
    ) + pdu
}
