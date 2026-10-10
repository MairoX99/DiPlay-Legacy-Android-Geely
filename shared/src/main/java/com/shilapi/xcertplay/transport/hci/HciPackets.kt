package com.shilapi.xcertplay.transport.hci

internal data class HciEventPacket(val code: Int, val parameters: ByteArray)

/** One ACL data packet as framed at the HCI level. */
internal data class HciAclData(
    val handle: Int,
    val packetBoundary: Int,
    val broadcast: Int,
    val payload: ByteArray,
)

internal object HciPackets {
    fun command(opcode: Int, parameters: ByteArray): ByteArray =
        byteArrayOf(
            (opcode and 0xFF).toByte(),
            ((opcode shr 8) and 0xFF).toByte(),
            parameters.size.toByte(),
        ) + parameters

    /**
     * The whole event, or null when the bytes are not one.
     *
     * A USB read can come back short — an interrupt endpoint hands back at most one packet's worth — and a
     * malformed packet must cost that packet rather than the reader thread.
     */
    fun event(bytes: ByteArray): HciEventPacket? = event(bytes, 0)

    /**
     * The event starting at [offset], or null when what follows is not a whole one.
     *
     * A read can also come back **long**: two events whose parameters happen to fill a bulk endpoint's
     * packet arrive together. The caller walks the buffer with this rather than parsing only the head.
     */
    fun event(bytes: ByteArray, offset: Int): HciEventPacket? {
        if (bytes.size - offset < EVENT_HEADER_BYTES) return null
        val declared = (bytes[offset + 1].toInt() and 0xFF) + EVENT_HEADER_BYTES
        if (offset + declared > bytes.size) return null
        return HciEventPacket(
            bytes[offset].toInt() and 0xFF,
            bytes.copyOfRange(offset + EVENT_HEADER_BYTES, offset + declared),
        )
    }

    /** How many bytes of the read one event occupies: its two header octets and its parameters. */
    fun eventBytes(event: HciEventPacket): Int = EVENT_HEADER_BYTES + event.parameters.size

    fun aclHandle(acl: HciAclData): Int = acl.handle

    /** L2CAP header is 4 bytes; the ACL header is 4 bytes. */
    internal const val ACL_HEADER_BYTES = 4

    /** An event's header is its code octet and its parameter-length octet. */
    internal const val EVENT_HEADER_BYTES = 2
}

/** Turns the byte stream of ACL bulk reads into whole ACL packets. */
internal class HciAclReassembler(private val onDrop: (Int) -> Unit = {}) {
    private var pending = ByteArray(0)

    /** Bytes held back waiting for the rest of their packet; visible so a desync can be tested. */
    val pendingBytes: Int get() = pending.size

    fun feed(bytes: ByteArray): List<HciAclData> {
        pending += bytes
        val packets = mutableListOf<HciAclData>()
        var offset = 0
        while (offset + HciPackets.ACL_HEADER_BYTES <= pending.size) {
            val handleAndFlags = (pending[offset].toInt() and 0xFF) or
                ((pending[offset + 1].toInt() and 0xFF) shl 8)
            val length = (pending[offset + 2].toInt() and 0xFF) or
                ((pending[offset + 3].toInt() and 0xFF) shl 8)
            if (offset + HciPackets.ACL_HEADER_BYTES + length > pending.size) break
            val start = offset + HciPackets.ACL_HEADER_BYTES
            packets += HciAclData(
                handle = handleAndFlags and 0x0FFF,
                packetBoundary = (handleAndFlags shr 12) and 0x03,
                broadcast = (handleAndFlags shr 14) and 0x03,
                payload = pending.copyOfRange(start, start + length),
            )
            offset = start + length
        }
        pending = if (offset == 0) pending else pending.copyOfRange(offset, pending.size)
        if (pending.size > MAX_PENDING_BYTES) {
            // A length field that never matches would otherwise pile up for as long as the link stays up, on a
            // head unit with about 26 MB free. Dropping what cannot be a packet costs the desync, not the app.
            onDrop(pending.size)
            pending = ByteArray(0)
        }
        return packets
    }

    private companion object {
        const val MAX_PENDING_BYTES = 64 * 1024
    }
}
