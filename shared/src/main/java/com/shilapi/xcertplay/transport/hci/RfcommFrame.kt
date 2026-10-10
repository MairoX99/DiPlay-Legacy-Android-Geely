package com.shilapi.xcertplay.transport.hci

internal enum class RfcommFrameType { SABM, UA, DM, DISC, UIH, MCC, UNKNOWN }

internal data class RfcommFrame(
    val dlci: Int,
    val type: RfcommFrameType,
    val command: Boolean,
    val pollFinal: Boolean,
    /** Only a UIH frame carries credits, and only when its poll/final bit is set. */
    val credits: Int?,
    /** Only an MCC frame carries a type. */
    val mccType: Int,
    val information: ByteArray,
)

/**
 * RFCOMM frames, TS 07.10 / Bluetooth RFCOMM, the same layout BlueZ and iOS use.
 *
 * The address octet carries the DLCI and the command/response bit. The control octet does not.
 * Length uses the EA bit in the least significant bit, and it counts the information field only.
 * Every frame ends with an FCS octet that is not part of that length. A UIH FCS covers the address
 * and control octets; every other frame's FCS also covers the length octets. An MCC message is a
 * UIH frame on DLCI 0 whose information field starts with the message type.
 */
internal object RfcommFrameCodec {
    const val MCC_PN = 0x20
    const val MCC_MSC = 0x38
    const val MCC_FCON = 0x28
    const val MCC_FCOFF = 0x18
    const val MCC_TEST = 0x08
    const val MCC_NSC = 0x04

    private val MCC_TYPES = intArrayOf(MCC_PN, MCC_MSC, MCC_FCON, MCC_FCOFF, MCC_TEST, MCC_NSC)

    fun encode(frame: RfcommFrame): ByteArray {
        val address = ((frame.dlci and 0x3F) shl 2) or (if (frame.command) 0x02 else 0x00) or 0x01
        val multiplex = frame.type == RfcommFrameType.MCC
        val pollFinal = frame.pollFinal && !multiplex
        val control = controlByte(if (multiplex) RfcommFrameType.UIH else frame.type, pollFinal)
        val credit = if (frame.type == RfcommFrameType.UIH && pollFinal) {
            byteArrayOf((frame.credits ?: 0).toByte())
        } else {
            ByteArray(0)
        }
        val information = if (multiplex) mccInformation(frame) else frame.information
        // The length counts the information field and nothing else, so the credit octet sits between it and the
        // data without being counted. A frame carrying only credits therefore declares a length of zero.
        val length = lengthBytes(information.size)
        val covered = if (frame.type == RfcommFrameType.UIH || multiplex) {
            byteArrayOf(address.toByte(), control.toByte())
        } else {
            byteArrayOf(address.toByte(), control.toByte()) + length
        }
        return byteArrayOf(address.toByte(), control.toByte()) + length + credit + information +
            byteArrayOf(fcs(covered).toByte())
    }

    /** Returns null when the bytes are not yet a whole frame, or its FCS does not check out. */
    fun decode(bytes: ByteArray): RfcommFrame? {
        if (bytes.size < MINIMUM_FRAME_BYTES) return null
        val address = bytes[0].toInt() and 0xFF
        val control = bytes[1].toInt() and 0xFF
        val lengthEa = bytes[2].toInt() and 0x01
        val lengthOctets = if (lengthEa == 1) 1 else 2
        if (bytes.size < 2 + lengthOctets) return null
        val length = if (lengthEa == 1) {
            (bytes[2].toInt() and 0xFF) ushr 1
        } else {
            ((bytes[2].toInt() and 0xFF) ushr 1) or ((bytes[3].toInt() and 0xFF) shl 7)
        }

        val dlci = (address shr 2) and 0x3F
        val basicType = typeOf(control)
        if (basicType == RfcommFrameType.UNKNOWN) return null
        val uih = basicType == RfcommFrameType.UIH
        val pollFinal = (control and 0x10) != 0
        // A UIH frame that has the poll/final bit set carries a credit octet between the length and the data, and
        // the length does not count it — so the frame is that one octet longer than the length says. The
        // multiplexer's own frames are UIH too and never carry one, since their address carries a zero DLCI.
        val creditBytes = if (uih && pollFinal && dlci != 0) 1 else 0
        val infoStart = 2 + lengthOctets
        val informationStart = infoStart + creditBytes
        val frameEnd = informationStart + length + 1
        if (length < 0 || bytes.size < frameEnd) return null

        val covered = if (uih) 2 else infoStart
        if (fcs(bytes.copyOfRange(0, covered)) != (bytes[frameEnd - 1].toInt() and 0xFF)) return null

        val body = if (length == 0) {
            ByteArray(0)
        } else {
            bytes.copyOfRange(informationStart, informationStart + length)
        }
        val command = (address and 0x02) != 0
        if (uih && dlci == 0) {
            mccFrame(command, body)?.let { return it }
        }

        return RfcommFrame(
            dlci = dlci,
            type = basicType,
            command = command,
            pollFinal = pollFinal,
            credits = if (creditBytes == 1) bytes[infoStart].toInt() and 0xFF else null,
            mccType = 0,
            information = body,
        )
    }

    /** CRC-8, initial value 0xFF, reflected polynomial 0xE0, final XOR 0xFF. */
    fun fcs(header: ByteArray): Int {
        var crc = 0xFF
        for (byte in header) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x01) != 0) (crc ushr 1) xor 0xE0 else crc ushr 1
            }
        }
        return (crc xor 0xFF) and 0xFF
    }

    /**
     * The eight octets of a Parameter Negotiation command or response, laid out as BlueZ `rfcomm_pn`:
     * the DLCI itself, the flow-control octet, one-byte acknowledgement timer, then the MTU.
     *
     * The flow-control octet is 0xF0 on a command and 0xE0 on a response. Answering with the command's own
     * octet back claims this end's flow control instead of accepting the phone's.
     */
    fun parameterNegotiation(
        dlci: Int,
        maxFrameSize: Int,
        initialCredits: Int,
        command: Boolean,
    ): ByteArray = byteArrayOf(
        (dlci and 0x3F).toByte(),
        (if (command) 0xF0 else 0xE0).toByte(),
        0,
        0,
        (maxFrameSize and 0xFF).toByte(),
        ((maxFrameSize shr 8) and 0xFF).toByte(),
        0,
        initialCredits.toByte(),
    )

    private fun mccInformation(frame: RfcommFrame): ByteArray {
        val type = ((frame.mccType and 0x3F) shl 2) or (if (frame.command) 0x02 else 0x00) or 0x01
        return byteArrayOf(type.toByte()) + lengthBytes(frame.information.size) + frame.information
    }

    private fun mccFrame(command: Boolean, body: ByteArray): RfcommFrame? {
        if (body.size < 2) return null
        val mccType = (body[0].toInt() ushr 2) and 0x3F
        if (mccType !in MCC_TYPES) return null
        val valueLengthEa = body[1].toInt() and 0x01
        val valueLengthOctets = if (valueLengthEa == 1) 1 else 2
        if (body.size < 1 + valueLengthOctets) return null
        val valueLength = if (valueLengthEa == 1) {
            (body[1].toInt() and 0xFF) ushr 1
        } else {
            ((body[1].toInt() and 0xFF) ushr 1) or ((body[2].toInt() and 0xFF) shl 7)
        }
        val valueStart = 1 + valueLengthOctets
        if (body.size < valueStart + valueLength) return null
        return RfcommFrame(
            dlci = 0,
            type = RfcommFrameType.MCC,
            command = command,
            pollFinal = false,
            credits = null,
            mccType = mccType,
            information = body.copyOfRange(valueStart, valueStart + valueLength),
        )
    }

    private fun controlByte(type: RfcommFrameType, pollFinal: Boolean): Int {
        val base = when (type) {
            RfcommFrameType.SABM -> 0x2F
            RfcommFrameType.UA -> 0x63
            RfcommFrameType.DM -> 0x0F
            RfcommFrameType.DISC -> 0x43
            RfcommFrameType.UIH -> 0xEF
            RfcommFrameType.MCC, RfcommFrameType.UNKNOWN -> error("cannot encode an unknown frame type")
        }
        return if (pollFinal) base or 0x10 else base
    }

    private fun typeOf(control: Int): RfcommFrameType = when (control and 0xEF) {
        0x2F -> RfcommFrameType.SABM
        0x63 -> RfcommFrameType.UA
        0x0F -> RfcommFrameType.DM
        0x43 -> RfcommFrameType.DISC
        0xEF -> RfcommFrameType.UIH
        else -> RfcommFrameType.UNKNOWN
    }

    private fun lengthBytes(length: Int): ByteArray = if (length < 0x80) {
        byteArrayOf(((length shl 1) or 0x01).toByte())
    } else {
        byteArrayOf(((length and 0x7F) shl 1).toByte(), (length ushr 7).toByte())
    }

    private const val MINIMUM_FRAME_BYTES = 4
}
