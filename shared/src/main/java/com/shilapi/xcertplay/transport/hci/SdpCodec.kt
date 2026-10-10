package com.shilapi.xcertplay.transport.hci


/**
 * SDP request building and response parsing.
 *
 * Two traps live here. Every SDP length and field is **big-endian** (unlike L2CAP and RFCOMM, which are
 * little-endian), and a data element declares its own size, so a request is only well-formed if each declared
 * length matches what follows it.
 */
internal object SdpCodec {
    const val PDU_ERROR_RESPONSE = 0x01
    const val PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST = 0x06
    const val PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE = 0x07

    const val ATTR_PROTOCOL_DESCRIPTOR_LIST = 0x0004

    /** The 16-bit UUID that marks the RFCOMM protocol in a ProtocolDescriptorList. */
    const val RFCOMM_UUID_16 = 0x0003

    private const val ELEMENT_SEQUENCE = 0x35
    private const val ELEMENT_UUID_16 = 0x19
    private const val ELEMENT_UINT_8 = 0x08
    private const val ELEMENT_UINT_16 = 0x09

    /**
     * UUID-128: type descriptor 3 in bits 5-3, size descriptor 4 in bits 2-0.
     *
     * Size descriptor 4 is "a fixed sixteen octets", so this one octet is the whole header: nothing sits
     * between it and the value. Size descriptor 5 — which is what [ELEMENT_SEQUENCE] carries — is the one
     * that is followed by a length octet.
     */
    private const val ELEMENT_UUID_128 = 0x1C

    private const val MAXIMUM_ATTRIBUTE_BYTE_COUNT = 0xFFFF
    private const val SDP_HEADER_BYTES = 5

    /** How much of an inbound request's parameters the log keeps. Nothing here asks with more. */
    private const val MAXIMUM_LOGGED_QUESTION_BYTES = 64
    private const val ELEMENT_UINT_32 = 0x0A

    /** `35` plus its one length octet. Every sequence this file writes is shorter than 128 bytes. */
    private const val SEQUENCE_HEADER_BYTES = 2

    const val ATTR_RECORD_HANDLE = 0x0000
    const val ATTR_SERVICE_CLASS_ID_LIST = 0x0001

    /**
     * The accessory-side iAP2 UUID as an SDP record carries it: the text order of
     * `00000000-deca-fade-deca-deafdecacaff`.
     *
     * Not the EIR's bytes, which hold the same UUID the other way round, and not the `…cafe` the phone is
     * asked about. The three spellings are not interchangeable, and copying one into another's field
     * declares a service nobody is looking for.
     */
    const val ACCESSORY_IAP2_UUID_TEXT = "00000000-deca-fade-deca-deafdecacaff"

    /** The RFCOMM channel this accessory's record publishes. The record is answered, never listened on. */
    private const val ACCESSORY_RFCOMM_CHANNEL = 1

    private const val L2CAP_UUID_16 = 0x0100

    /**
     * This accessory's own record: the three attributes iAP2 needs and no more — a handle, the accessory-side
     * service class, and the protocol list naming L2CAP and the RFCOMM channel above.
     */
    private val accessoryAttributes: List<ByteArray> = listOf(
        uint16Element(ATTR_RECORD_HANDLE) + uint32Element(0x00010001),
        uint16Element(ATTR_SERVICE_CLASS_ID_LIST) + sequence(uuid128Element(ACCESSORY_IAP2_UUID_TEXT)),
        uint16Element(ATTR_PROTOCOL_DESCRIPTOR_LIST) +
            sequence(
                sequence(uuid16Element(L2CAP_UUID_16)) +
                    sequence(uuid16Element(RFCOMM_UUID_16) + uint8Element(ACCESSORY_RFCOMM_CHANNEL)),
            ),
    )

    /**
     * The 128-bit UUID as its 16 bytes in canonical text order — no reversal.
     *
     * The iAP2 service name is written on the phone in one fixed way, so there is no second arrangement to try.
     * Hosts that reverse it do so because their chip wants it pre-reversed and flips it back; we assemble the
     * packet ourselves, so the order on the wire is the order in the text.
     */
    fun uuid128(value: String): ByteArray {
        val hex = value.replace("-", "")
        require(hex.length == 32) { "UUID-128 must have 32 hex digits: $value" }
        return ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun serviceSearchAttributeRequest(
        transactionId: Int,
        uuid: ByteArray,
        attributeIds: IntArray,
        continuation: ByteArray,
    ): ByteArray {
        // The UUID-128 element is its header octet and its sixteen octets, and nothing else. A length octet
        // used to be written between them (0x10), which is what an EIR's [length][type][value] framing looks
        // like but not what a data element is: a parser reading the element by its own rules takes 0x10 as the
        // UUID's first octet, is left with one stray octet inside the sequence, and the pattern matches no
        // record at all. The phone then answers with an empty result, which reads exactly like a phone that
        // has no iAP2 service.
        val pattern = sequence(byteArrayOf(ELEMENT_UUID_128.toByte()) + uuid)
        val attributes = sequence(
            attributeIds.flatMap { id ->
                listOf(ELEMENT_UINT_16.toByte(), (id shr 8).toByte(), id.toByte())
            }.toByteArray(),
        )
        val parameters = pattern +
            // MaximumAttributeByteCount is a uint16, so 0x09 is its whole header and the two octets behind
            // it are the value. A length octet (0x02) used to be written between the two, the same mistake
            // as the one above and just as fatal: the value read as 0x02FF and one stray octet was left over.
            byteArrayOf(ELEMENT_UINT_16.toByte()) + bigWord(MAXIMUM_ATTRIBUTE_BYTE_COUNT) +
            attributes +
            byteArrayOf(continuation.size.toByte()) + continuation
        return byteArrayOf(
            PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST.toByte(),
            (transactionId shr 8).toByte(),
            transactionId.toByte(),
        ) + bigWord(parameters.size) + parameters
    }

    /**
     * Reads a Service Search Attribute Request far enough to answer it: the transaction to echo, the phone's
     * limit on the attribute list, and where a previous answer of ours left off.
     *
     * The search pattern and the attribute list are stepped over by their own declared sizes rather than
     * interpreted. This stack publishes one record, and every query about it is answered with that record, so
     * which service the phone named cannot change the reply.
     */
    fun parseSearchAttributeRequest(bytes: ByteArray): SdpSearchAttributeRequest? {
        if (bytes.size < SDP_HEADER_BYTES) return null
        if ((bytes[0].toInt() and 0xFF) != PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST) return null
        val parameterLength = bigWord(bytes, 3)
        if (parameterLength < 0) return null
        val parameters = bytes.copyOfRange(SDP_HEADER_BYTES, minOf(SDP_HEADER_BYTES + parameterLength, bytes.size))

        val patternSize = elementSize(parameters, 0)
        if (patternSize < 0) return null
        // MaximumAttributeByteCount is a uint16, so its header octet is the whole header and the limit is the
        // two octets behind it.
        val maximumOffset = patternSize
        if (maximumOffset + 3 > parameters.size) return null
        if (parameters[maximumOffset].toInt() and 0xFF != ELEMENT_UINT_16) return null
        val maximum = bigWord(parameters, maximumOffset + 1)
        if (maximum < 0) return null

        val attributeIdsOffset = maximumOffset + 3
        val attributeIdsSize = elementSize(parameters, attributeIdsOffset)
        if (attributeIdsSize < 0) return null
        val continuationOffset = attributeIdsOffset + attributeIdsSize
        val continuation = if (continuationOffset < parameters.size) {
            val length = parameters[continuationOffset].toInt() and 0xFF
            if (length > 0 && continuationOffset + 1 + length <= parameters.size) {
                parameters.copyOfRange(continuationOffset + 1, continuationOffset + 1 + length)
            } else {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        return SdpSearchAttributeRequest(
            transactionId = bigWord(bytes, 1),
            maximumAttributeByteCount = maximum,
            continuation = continuation,
            question = parameters.take(MAXIMUM_LOGGED_QUESTION_BYTES).joinToString(" ") { octet ->
                val value = octet.toInt() and 0xFF
                "0123456789ABCDEF"[value ushr 4].toString() + "0123456789ABCDEF"[value and 0x0F]
            },
        )
    }

    /**
     * Answers a Service Search Attribute Request with [accessoryAttributes], echoing [transactionId].
     *
     * The answer's AttributeLists is one sequence holding one sequence per service record — two levels, not one.
     * A phone reading two levels finds no service class and no protocol list in a one-level answer, so it reads
     * as an accessory that does not publish iAP2 at all; the byte count therefore covers both headers.
     *
     * [maximumAttributeByteCount] is the phone's own limit on the attribute lists, and the whole record is far
     * below any limit a phone asks with, so the ordinary answer is one reply with an empty continuation. A
     * limit that cannot hold the record is honoured by ending at an attribute boundary — half an attribute is
     * not an attribute — with the continuation naming where to resume. At least one attribute always goes
     * out, since a reply carrying none could never advance.
     */
    fun accessoryServiceSearchAttributeResponse(
        transactionId: Int,
        maximumAttributeByteCount: Int,
        continuation: ByteArray,
    ): ByteArray {
        val start = (continuation.firstOrNull()?.toInt()?.and(0xFF) ?: 0)
            .coerceIn(0, accessoryAttributes.size)
        val included = mutableListOf<ByteArray>()
        var contentBytes = 0
        var next = start
        while (next < accessoryAttributes.size) {
            val attribute = accessoryAttributes[next]
            val overflowed = 2 * SEQUENCE_HEADER_BYTES + contentBytes + attribute.size > maximumAttributeByteCount
            if (included.isNotEmpty() && overflowed) break
            included += attribute
            contentBytes += attribute.size
            next += 1
        }
        val attributeLists = sequence(
            sequence(included.fold(ByteArray(0)) { all, attribute -> all + attribute }),
        )
        val resume = if (next < accessoryAttributes.size) byteArrayOf(next.toByte()) else ByteArray(0)
        val parameters = bigWord(attributeLists.size) + attributeLists + byteArrayOf(resume.size.toByte()) + resume
        return byteArrayOf(
            PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE.toByte(),
            (transactionId shr 8).toByte(),
            transactionId.toByte(),
        ) + bigWord(parameters.size) + parameters
    }

    fun parseSearchAttributeResponse(bytes: ByteArray): SdpResponse {
        if (bytes.size < SDP_HEADER_BYTES) return SdpResponse(bytes.firstOrNull()?.toInt()?.and(0xFF) ?: -1, 0, emptyList(), ByteArray(0))
        val pduId = bytes[0].toInt() and 0xFF
        if (pduId == PDU_ERROR_RESPONSE) {
            return SdpResponse(bigWord(bytes, 5), 0, emptyList(), ByteArray(0))
        }
        if (pduId != PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE) {
            return SdpResponse(pduId, 0, emptyList(), ByteArray(0))
        }

        val parameterLength = bigWord(bytes, 3)
        val parameters = bytes.copyOfRange(SDP_HEADER_BYTES, minOf(SDP_HEADER_BYTES + parameterLength, bytes.size))
        if (parameters.size < 2) return SdpResponse(null, 0, emptyList(), ByteArray(0))

        val attributeListsBytes = bigWord(parameters, 0)
        val end = minOf(2 + attributeListsBytes, parameters.size)
        // The byte count covers one sequence, and each service record is a sequence inside it. Reading the
        // elements at this level instead — one level too shallow — lands on that outer sequence as if it were a
        // single record, whose one item is the real record where an attribute ID should be, and so reports "no
        // iAP2 service on this iPhone" for a phone that named one.
        val lists = if (end <= 2) emptyList() else sequenceItems(parameters.copyOfRange(2, end))

        val continuationLength = if (end < parameters.size) parameters[end].toInt() and 0xFF else 0
        val continuation = if (continuationLength > 0 && end + 1 + continuationLength <= parameters.size) {
            parameters.copyOfRange(end + 1, end + 1 + continuationLength)
        } else {
            ByteArray(0)
        }
        return SdpResponse(null, lists.size, lists, continuation)
    }

    /** The value of attribute [attributeId] inside one service record, or null when the record omits it. */
    fun attributeValue(record: ByteArray, attributeId: Int): ByteArray? {
        val items = sequenceItems(record)
        var index = 0
        while (index + 1 < items.size) {
            if (unsignedIntOf(items[index]) == attributeId) return items[index + 1]
            index += 2
        }
        return null
    }

    /** The RFCOMM channel from a ProtocolDescriptorList value, or null when it does not name one. */
    fun rfcommChannelFromProtocolDescriptorList(value: ByteArray): Int? {
        sequenceItems(value).forEach { protocol ->
            val parts = sequenceItems(protocol)
            val uuid = parts.firstOrNull()?.let(::uuidOf)
            if (uuid == RFCOMM_UUID_16) return parts.getOrNull(1)?.let(::unsignedIntOf)
        }
        return null
    }

    /** The data elements directly inside a sequence element, as byte ranges. */
    private fun sequenceItems(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val headerBytes = headerBytes(bytes[0].toInt() and 0xFF)
        if (headerBytes <= 0 || bytes.size < headerBytes) return emptyList()
        val declared = declaredLength(bytes, headerBytes)
        if (declared < 0) return emptyList()
        val contentEnd = minOf(headerBytes + declared, bytes.size)
        val items = mutableListOf<ByteArray>()
        var offset = headerBytes
        while (offset < contentEnd) {
            val size = elementSize(bytes, offset)
            if (size <= 0 || offset + size > contentEnd) break
            items += bytes.copyOfRange(offset, offset + size)
            offset += size
        }
        return items
    }

    /** The total size in bytes of the data element starting at [offset], or -1. */
    private fun elementSize(bytes: ByteArray, offset: Int): Int {
        if (offset >= bytes.size) return -1
        val headerBytes = headerBytes(bytes[offset].toInt() and 0xFF)
        if (headerBytes <= 0) return -1
        val declared = declaredLength(bytes, headerBytes, offset)
        if (declared < 0) return -1
        return headerBytes + declared
    }

    /** Bytes the element header occupies: the type octet, plus the size octet(s) when it uses them. */
    private fun headerBytes(first: Int): Int = when (first and 0x07) {
        0, 1, 2, 3, 4 -> 1
        5 -> 2
        6 -> 3
        7 -> 5
        else -> -1
    }

    private fun declaredLength(bytes: ByteArray, headerBytes: Int, offset: Int = 0): Int {
        val descriptor = bytes[offset].toInt() and 0x07
        return when (descriptor) {
            0 -> 1
            1 -> 2
            2 -> 4
            3 -> 8
            4 -> 16
            5 -> bytes.getOrNull(offset + 1)?.toInt()?.and(0xFF) ?: -1
            6 -> if (offset + 2 < bytes.size) {
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset + 2].toInt() and 0xFF)
            } else {
                -1
            }
            else -> -1
        }
    }

    private fun uuidOf(element: ByteArray): Int? {
        val first = element.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return when (first) {
            ELEMENT_UUID_16 -> if (element.size >= 3) {
                ((element[1].toInt() and 0xFF) shl 8) or (element[2].toInt() and 0xFF)
            } else {
                null
            }
            else -> null
        }
    }

    private fun unsignedIntOf(element: ByteArray): Int? {
        val first = element.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return when (first) {
            ELEMENT_UINT_8 -> element.getOrNull(1)?.toInt()?.and(0xFF)
            ELEMENT_UINT_16 -> if (element.size >= 3) {
                ((element[1].toInt() and 0xFF) shl 8) or (element[2].toInt() and 0xFF)
            } else {
                null
            }
            else -> null
        }
    }

    private fun uint8Element(value: Int) = byteArrayOf(ELEMENT_UINT_8.toByte(), value.toByte())

    private fun uint16Element(value: Int) =
        byteArrayOf(ELEMENT_UINT_16.toByte(), (value shr 8).toByte(), value.toByte())

    private fun uint32Element(value: Int) = byteArrayOf(
        ELEMENT_UINT_32.toByte(),
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun uuid16Element(value: Int) =
        byteArrayOf(ELEMENT_UUID_16.toByte(), (value shr 8).toByte(), value.toByte())

    /** A UUID-128 element is its header octet and its sixteen octets, with no length octet between them. */
    private fun uuid128Element(text: String) = byteArrayOf(ELEMENT_UUID_128.toByte()) + uuid128(text)

    private fun sequence(content: ByteArray): ByteArray =
        byteArrayOf(ELEMENT_SEQUENCE.toByte(), content.size.toByte()) + content

    private fun bigWord(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

    private fun bigWord(bytes: ByteArray, offset: Int): Int =
        if (offset + 1 < bytes.size) {
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        } else {
            -1
        }
}

internal data class SdpResponse(
    val errorCode: Int?,
    val totalServiceRecords: Int,
    val attributeLists: List<ByteArray>,
    val continuation: ByteArray,
)

internal data class SdpSearchAttributeRequest(
    val transactionId: Int,
    val maximumAttributeByteCount: Int,
    val continuation: ByteArray,
    /**
     * The request's parameters as space-separated hex octets, in wire order: service search pattern, maximum
     * attribute byte count, attribute ID list, continuation state.
     *
     * Kept for the log alone. One record is published here, so the question cannot change the answer — but a
     * phone that asked for an attribute this record does not carry and a phone that never got an answer at all
     * look the same from our side, and the parameters are the only thing that tells them apart.
     *
     * The octets are separated because the log's redactor takes any unbroken run of 24 or more hex digits for
     * an opaque identifier and replaces it, which would leave nothing of this behind.
     */
    val question: String,
)
