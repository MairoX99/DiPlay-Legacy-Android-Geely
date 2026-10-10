package com.shilapi.xcertplay.transport.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdpCodecTest {
    @Test fun uuid128IsPackedFromTheCanonicalTextInOrder() {
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00,
                0xDE.toByte(), 0xCA.toByte(), 0xFA.toByte(), 0xDE.toByte(),
                0xDE.toByte(), 0xCA.toByte(), 0xDE.toByte(), 0xAF.toByte(),
                0xDE.toByte(), 0xCA.toByte(), 0xCA.toByte(), 0xFE.toByte(),
            ),
            SdpCodec.uuid128(ActionsBluetooth.IAP2_UUID_128),
        )
    }

    @Test fun searchAttributeRequestCarriesThePatternTheAttributesAndNoContinuation() {
        val uuid = SdpCodec.uuid128(ActionsBluetooth.IAP2_UUID_128)
        val request = SdpCodec.serviceSearchAttributeRequest(
            transactionId = 0x1234,
            uuid = uuid,
            attributeIds = intArrayOf(SdpCodec.ATTR_PROTOCOL_DESCRIPTOR_LIST),
            continuation = ByteArray(0),
        )

        assertEquals(SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST, request[0].toInt() and 0xFF)
        assertEquals(0x12, request[1].toInt() and 0xFF)
        assertEquals(0x34, request[2].toInt() and 0xFF)
        assertEquals(request.size - 5, ((request[3].toInt() and 0xFF) shl 8) or (request[4].toInt() and 0xFF))

        // ServiceSearchPattern: a sequence holding one UUID-128 element. 0x1C is type UUID (3) with size
        // descriptor 4 — a fixed sixteen octets — so it is one header octet and then straight into the value.
        // Seventeen octets in all, which is why the sequence says 0x11. An extra length octet where the value
        // starts makes this 0x12 and shifts every octet behind it, and the query then matches no record.
        assertEquals(0x35, request[5].toInt() and 0xFF)
        assertEquals(0x11, request[6].toInt() and 0xFF)
        assertEquals(1 + uuid.size, request[6].toInt() and 0xFF)
        assertEquals(0x1C, request[7].toInt() and 0xFF)
        assertArrayEquals(uuid, request.copyOfRange(8, 24))

        // MaximumAttributeByteCount: 0x09 is uint16 and the two octets behind it are the value. A length octet
        // in between is the same mistake again.
        assertEquals(0x09, request[24].toInt() and 0xFF)
        assertEquals(0xFF, request[25].toInt() and 0xFF)
        assertEquals(0xFF, request[26].toInt() and 0xFF)

        // The attribute id list, then an empty continuation state.
        assertEquals(0x35, request[27].toInt() and 0xFF)
        assertEquals(3, request[28].toInt() and 0xFF)
        assertEquals(0x09, request[29].toInt() and 0xFF)
        assertEquals(0x00, request[30].toInt() and 0xFF)
        assertEquals(0x04, request[31].toInt() and 0xFF)
        assertEquals(0, request[32].toInt() and 0xFF)
        assertEquals(33, request.size)
    }

    @Test fun aContinuationStateIsCarriedBackInTheNextRequest() {
        val request = SdpCodec.serviceSearchAttributeRequest(
            transactionId = 0x1234,
            uuid = SdpCodec.uuid128(ActionsBluetooth.IAP2_UUID_128),
            attributeIds = intArrayOf(SdpCodec.ATTR_PROTOCOL_DESCRIPTOR_LIST),
            continuation = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )

        assertEquals(2, request[request.size - 3].toInt() and 0xFF)
        assertEquals(0xAA, request[request.size - 2].toInt() and 0xFF)
        assertEquals(0xBB, request[request.size - 1].toInt() and 0xFF)
    }

    @Test fun rfcommChannelIsReadFromTheProtocolDescriptorList() {
        // sequence { sequence { uuid-16 0x0003, uint8 3 } }
        val value = byteArrayOf(
            0x35, 0x07,
            0x35, 0x05,
            0x19, 0x00, 0x03,
            0x08, 0x03,
        )
        assertEquals(3, SdpCodec.rfcommChannelFromProtocolDescriptorList(value))
    }

    @Test fun aProtocolDescriptorListWithoutRfcommYieldsNull() {
        val value = byteArrayOf(0x35, 0x03, 0x19, 0x01, 0x00)
        assertNull(SdpCodec.rfcommChannelFromProtocolDescriptorList(value))
    }

    /** A service record is a sequence of (attribute id, value) pairs. */
    @Test fun anAttributeValueIsFoundByItsIdInsideARecord() {
        val record = byteArrayOf(
            0x35, 0x0C,
            0x09, 0x00, 0x04,
            0x35, 0x07, 0x35, 0x05, 0x19, 0x00, 0x03, 0x08, 0x03,
        )

        val value = SdpCodec.attributeValue(record, SdpCodec.ATTR_PROTOCOL_DESCRIPTOR_LIST)!!

        assertEquals(3, SdpCodec.rfcommChannelFromProtocolDescriptorList(value))
    }

    @Test fun aRecordWithoutTheWantedAttributeHasNoValue() {
        val record = byteArrayOf(0x35, 0x03, 0x09, 0x00, 0x00)
        assertNull(SdpCodec.attributeValue(record, SdpCodec.ATTR_PROTOCOL_DESCRIPTOR_LIST))
    }

    /** An error code is big-endian, like every other SDP length and field. */
    @Test fun anErrorResponseIsReportedAsAnErrorNotAsEmptyResults() {
        val response = SdpCodec.parseSearchAttributeResponse(
            byteArrayOf(0x01, 0x12, 0x34, 0x00, 0x02, 0x00, 0x06),
        )
        assertEquals(0x0006, response.errorCode)
        assertEquals(0, response.totalServiceRecords)
        // Anything that is not a search-attribute response is reported by its own PDU id.
        assertEquals(0x05, SdpCodec.parseSearchAttributeResponse(byteArrayOf(0x05, 0, 0, 0, 0)).errorCode)
    }

    @Test fun anAttributeListByteCountThatDoesNotAddUpStopsInsteadOfThrowing() {
        val response = SdpCodec.parseSearchAttributeResponse(
            // Says four attribute-list bytes follow, then supplies a truncated sequence.
            byteArrayOf(0x07, 0x12, 0x34, 0x00, 0x05, 0x00, 0x04, 0x35, 0x09, 0x00),
        )
        assertEquals(0, response.totalServiceRecords)
    }
}
