package com.shilapi.xcertplay.transport.hci

/**
 * L2CAP PDUs and signaling commands, as bytes.
 *
 * Every length in this header is little-endian; every length in SDP is big-endian. They are easy to confuse, so
 * each is pinned by a test.
 */
internal object L2capCodec {
    const val SIGNALING_CID = 0x0001
    const val PSM_SDP = 0x0001
    const val PSM_RFCOMM = 0x0003

    const val CODE_COMMAND_REJECT = 0x01
    const val CODE_CONNECTION_REQUEST = 0x02
    const val CODE_CONNECTION_RESPONSE = 0x03
    const val CODE_CONFIGURE_REQUEST = 0x04
    const val CODE_CONFIGURE_RESPONSE = 0x05
    const val CODE_DISCONNECTION_REQUEST = 0x06
    const val CODE_DISCONNECTION_RESPONSE = 0x07
    const val CODE_INFORMATION_REQUEST = 0x0A
    const val CODE_INFORMATION_RESPONSE = 0x0B

    const val CONNECTION_SUCCESS = 0x0000

    /**
     * The only Configure Response result that leaves a channel configured.
     *
     * The others are not failures to be shrugged off. Linux's `l2cap_config_rsp` answers 0x0001 (unacceptable
     * parameters) and 0x0003 (unknown options) by **re-negotiating** — parsing the options the peer sent back
     * and sending a corrected Configure Request — and 0x0004 (pending) by waiting for the answer that follows.
     * Only on 0x0000 does it clear the pending flag and let the channel reach the connected state.
     */
    const val CONFIG_SUCCESS = 0x0000

    /** Command Reject reasons. */
    const val REASON_COMMAND_NOT_UNDERSTOOD = 0x0000
    const val REASON_INVALID_CID = 0x0002

    /** Configuration option types. */
    const val OPTION_MTU = 0x01

    const val HEADER_BYTES = 4
    const val SIGNALING_HEADER_BYTES = 4

    /** A PDU: length(2, LE) + channel id(2, LE) + payload. */
    fun pdu(cid: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            (payload.size and 0xFF).toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),
            (cid and 0xFF).toByte(),
            ((cid shr 8) and 0xFF).toByte(),
        ) + payload

    fun pduCid(pdu: ByteArray): Int = word(pdu, 2)

    fun pduPayload(pdu: ByteArray): ByteArray = pdu.copyOfRange(HEADER_BYTES, pdu.size)

    /** A signaling command: code(1) + identifier(1) + length(2, LE) + data. */
    fun signaling(code: Int, identifier: Int, data: ByteArray): ByteArray =
        byteArrayOf(
            code.toByte(),
            identifier.toByte(),
            (data.size and 0xFF).toByte(),
            ((data.size shr 8) and 0xFF).toByte(),
        ) + data

    fun signalingCode(payload: ByteArray): Int = payload[0].toInt() and 0xFF

    fun signalingIdentifier(payload: ByteArray): Int = payload[1].toInt() and 0xFF

    fun signalingData(payload: ByteArray): ByteArray =
        payload.copyOfRange(SIGNALING_HEADER_BYTES, payload.size)

    fun connectionRequest(psm: Int, sourceCid: Int): ByteArray =
        halfWord(psm) + halfWord(sourceCid)

    fun connectionResponse(destinationCid: Int, sourceCid: Int, result: Int, status: Int): ByteArray =
        halfWord(destinationCid) + halfWord(sourceCid) + halfWord(result) + halfWord(status)

    /**
     * Command Reject, "Invalid CID": reason, then the channel at this end, then the one at the peer's — the
     * payload Linux's `l2cap_cmd_rej_cid` carries and `cmd_reject_invalid_cid` fills.
     *
     * A command naming a channel this end has no record of gets this rather than an answer: any answer would
     * have to name that channel back, and a value invented for it is what makes the far end drop a link that
     * was working.
     */
    fun commandRejectInvalidCid(scid: Int, dcid: Int): ByteArray =
        halfWord(REASON_INVALID_CID) + halfWord(scid) + halfWord(dcid)

    fun configurationOptionMtu(mtu: Int): ByteArray =
        byteArrayOf(OPTION_MTU.toByte(), 0x02) + halfWord(mtu)

    /** Walks `type(1) / length(1) / value(length)`; a truncated or lying length ends the scan. */
    fun configurationOptions(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val options = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset + 2 <= bytes.size) {
            val type = bytes[offset].toInt() and 0xFF
            val length = bytes[offset + 1].toInt() and 0xFF
            val start = offset + 2
            if (start + length > bytes.size) break
            options += type to bytes.copyOfRange(start, start + length)
            offset = start + length
        }
        return options
    }

    private fun word(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun halfWord(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
}
