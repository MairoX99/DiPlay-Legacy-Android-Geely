package com.shilapi.xcertplay.transport.hci

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpClientTest {
    /**
     * The iPhone's half of the conversation: it answers L2CAP signaling and replies to an SDP query with
     * [records] service records naming RFCOMM channel [channel].
     *
     * It walks the search pattern the way a server does and answers with nothing when the pattern is not
     * well formed, which is what the phone does: a malformed request does not produce an error, it produces
     * an empty result, and an empty result is indistinguishable from a phone that has no iAP2 service.
     */
    private class Peer {
        var channel = 3
        var records = 1

        /** The phone asks *us* for our service records on the same channel before answering our query. */
        var sendsARequestFirst = false
        var onData: ((Int, ByteArray) -> Unit)? = null

        val sent = CopyOnWriteArrayList<ByteArray>()
        private var peerCid = -1

        /** What the last query asked for, or null when its search pattern did not parse. */
        var searchedUuid: ByteArray? = null
            private set

        /** The requester's channel id, learned from its Connection Request. */
        private var remoteCid = -1

        val engine: L2capEngine = L2capEngine(
            object : AclSender {
                override val aclPayloadBytes = 600
                override fun sendAclPayload(handle: Int, packetBoundary: Int, payload: ByteArray) {
                    sent += payload
                    answer(handle, payload)
                }
            },
            { cid, _, data -> onData?.invoke(cid, data) },
        )

        private fun answer(handle: Int, payload: ByteArray) {
            val cid = L2capCodec.pduCid(payload)
            if (cid != L2capCodec.SIGNALING_CID) {
                val request = L2capCodec.pduPayload(payload)
                // Only a query is answered. The accessory answering one of ours is not a query, and feeding
                // the request again for it would have the two sides answering each other for ever.
                if (request.firstOrNull()?.toInt()?.and(0xFF) != SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST) {
                    return
                }
                searchedUuid = searchPatternUuid(request)
                // Data arrives addressed to one of our channels; the reply is addressed to theirs.
                if (sendsARequestFirst) feed(handle, remoteCid, ownRequest())
                feed(handle, remoteCid, sdpResponse())
                return
            }
            val signaling = L2capCodec.pduPayload(payload)
            val identifier = L2capCodec.signalingIdentifier(signaling)
            val data = L2capCodec.signalingData(signaling)
            when (L2capCodec.signalingCode(signaling)) {
                L2capCodec.CODE_CONNECTION_REQUEST -> {
                    peerCid = 0x0041
                    remoteCid = word(data, 2)
                    reply(
                        handle,
                        L2capCodec.CODE_CONNECTION_RESPONSE,
                        identifier,
                        byteArrayOf(peerCid.toByte(), 0x00) +
                            byteArrayOf(remoteCid.toByte(), 0x00) +
                            byteArrayOf(0x00, 0x00, 0x00, 0x00),
                    )
                }
                L2capCodec.CODE_CONFIGURE_REQUEST -> {
                    reply(
                        handle,
                        L2capCodec.CODE_CONFIGURE_RESPONSE,
                        identifier,
                        byteArrayOf(remoteCid.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00),
                    )
                    // Both ends configure, so the peer sends its own request too.
                    reply(
                        handle,
                        L2capCodec.CODE_CONFIGURE_REQUEST,
                        9,
                        byteArrayOf(remoteCid.toByte(), 0x00, 0x00, 0x00) +
                            L2capCodec.configurationOptionMtu(672),
                    )
                }
            }
        }

        private fun reply(handle: Int, code: Int, identifier: Int, data: ByteArray) {
            feed(handle, L2capCodec.SIGNALING_CID, L2capCodec.signaling(code, identifier, data))
        }

        private fun feed(handle: Int, cid: Int, payload: ByteArray) {
            engine.handleAcl(HciAclData(handle, 0, 0, L2capCodec.pdu(cid, payload)))
        }

        /**
         * A ServiceSearchAttributeRequest: the phone asking us for our records. Even PDU id — a question.
         *
         * Laid out the way the phone lays one out: a search pattern element, the limit it will read, the
         * attributes it wants, and no continuation.
         */
        private fun ownRequest(): ByteArray {
            val pattern = byteArrayOf(0x35, 0x03, 0x19, 0x01, 0x00)
            val attributes = byteArrayOf(0x35, 0x03, 0x09, 0x00, 0x04)
            val parameters =
                pattern + byteArrayOf(0x09, 0xFF.toByte(), 0xFF.toByte()) + attributes + byteArrayOf(0x00)
            return byteArrayOf(SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST.toByte(), 0x00, 0x01) +
                bigWord(parameters.size) + parameters
        }

        /** The same PDU with no parameters: a question nothing can be read out of. */
        fun requestWithoutParameters(): ByteArray =
            byteArrayOf(SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST.toByte(), 0x00, 0x02, 0x00, 0x00)

        /**
         * The answer the accessory has to send, written out byte for byte rather than built from the code
         * under test: a ServiceSearchAttributeResponse echoing transaction 1, with no continuation, whose
         * AttributeLists are one 51-byte sequence holding the one 49-byte sequence of attributes.
         */
        fun accessoryRecordAnswer(): ByteArray = byteArrayOf(
            0x07, 0x00, 0x01, 0x00, 0x36,
            // AttributeListsByteCount = 51, continuation empty at the end.
            0x00, 0x33,
            0x35, 0x31,
            0x35, 0x2F,
            // 0x0000 RecordHandle = 0x00010001.
            0x09, 0x00, 0x00, 0x0A, 0x00, 0x01, 0x00, 0x01,
            // 0x0001 ServiceClassIDList = { 00000000-deca-fade-deca-deafdecacaff }, text order.
            0x09, 0x00, 0x01, 0x35, 0x11, 0x1C,
            0x00, 0x00, 0x00, 0x00, 0xDE.toByte(), 0xCA.toByte(), 0xFA.toByte(), 0xDE.toByte(),
            0xDE.toByte(), 0xCA.toByte(), 0xDE.toByte(), 0xAF.toByte(),
            0xDE.toByte(), 0xCA.toByte(), 0xCA.toByte(), 0xFF.toByte(),
            // 0x0004 ProtocolDescriptorList = L2CAP 0x0100, then RFCOMM 0x0003 on channel 1.
            0x09, 0x00, 0x04, 0x35, 0x0C,
            0x35, 0x03, 0x19, 0x01, 0x00,
            0x35, 0x05, 0x19, 0x00, 0x03, 0x08, 0x01,
            0x00,
        )

        /**
         * The phone's answer: the attribute lists are one sequence holding one sequence per matching record, so
         * the byte count covers the outer header too. With nothing matched it covers an empty sequence, the two
         * bytes a count of this parameter is never smaller than.
         */
        private fun sdpResponse(): ByteArray {
            val matched = if (searchedUuid == null) {
                ByteArray(0)
            } else {
                (0 until records).fold(ByteArray(0)) { all, _ -> all + record(channel) }
            }
            val attributeLists = sequence(matched)
            val parameters = bigWord(attributeLists.size) + attributeLists + byteArrayOf(0x00)
            return byteArrayOf(0x07, 0x12, 0x34) + bigWord(parameters.size) + parameters
        }

        private fun sequence(content: ByteArray): ByteArray =
            byteArrayOf(0x35, content.size.toByte()) + content

        /**
         * The ServiceSearchPattern, walked the way a server walks it: a sequence whose declared length has to
         * account for exactly what is inside it, holding one UUID-128 element. `0x1C` is type UUID with size
         * descriptor 4 — a fixed sixteen octets — so the element is that header octet and sixteen octets and
         * nothing else; an extra length octet between them makes the element 18 octets and this null.
         */
        private fun searchPatternUuid(request: ByteArray): ByteArray? {
            val parameterLength = ((request[3].toInt() and 0xFF) shl 8) or (request[4].toInt() and 0xFF)
            if (parameterLength < 20 || 5 + parameterLength > request.size) return null
            if (request[5].toInt() and 0xFF != 0x35) return null
            val declared = request[6].toInt() and 0xFF
            val content = request.copyOfRange(7, 7 + declared)
            if (2 + declared > parameterLength) return null
            if (content.size != 17 || content[0].toInt() and 0xFF != 0x1C) return null
            return content.copyOfRange(1, 17)
        }

        /** One record: attribute 0x0004 whose value is sequence { sequence { uuid16 0x0003, uint8 channel } }. */
        private fun record(channel: Int): ByteArray {
            val protocolDescriptorList = byteArrayOf(
                0x35, 0x07,
                0x35, 0x05,
                0x19, 0x00, 0x03,
                0x08, channel.toByte(),
            )
            val content = byteArrayOf(0x09, 0x00, 0x04) + protocolDescriptorList
            return byteArrayOf(0x35, content.size.toByte()) + content
        }

        private fun word(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun bigWord(value: Int): ByteArray =
            byteArrayOf((value shr 8).toByte(), value.toByte())
    }

    @Test fun returnsTheChannelIosAdvertises() {
        val peer = Peer()
        peer.channel = 3
        val client = SdpClient(peer.engine)
        peer.onData = client::onData

        assertEquals(3, client.findRfcommChannel(handle = 0x0B))
    }

    @Test fun asksForTheChannelThePhoneActuallyAdvertises() {
        val peer = Peer()
        peer.channel = 7
        val client = SdpClient(peer.engine)
        peer.onData = client::onData

        assertEquals(7, client.findRfcommChannel(handle = 0x0B))
    }

    @Test fun aPhoneWithoutTheServiceFailsWithAClearMessage() {
        val peer = Peer()
        peer.records = 0
        val client = SdpClient(peer.engine)
        peer.onData = client::onData

        val failure = runCatching { client.findRfcommChannel(handle = 0x0B) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("iAP2"))
    }

    /** The query must carry the iAP2 UUID, in the arrangement the codec packs. */
    @Test fun theQueryNamesTheIap2Service() {
        val peer = Peer()
        val client = SdpClient(peer.engine)
        peer.onData = client::onData

        client.findRfcommChannel(handle = 0x0B)

        val query = peer.sent.first { L2capCodec.pduCid(it) != L2capCodec.SIGNALING_CID }
        // The query goes out addressed to the phone's channel, not to ours.
        assertEquals(0x0041, L2capCodec.pduCid(query))
        val request = L2capCodec.pduPayload(query)
        assertEquals(SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST, request[0].toInt() and 0xFF)
        assertTrue(request.size > 25)
    }

    /**
     * The pattern has to be a well-formed sequence for the phone to match anything against it. It was not:
     * a length octet was written inside the fixed-size UUID-128 element, so the phone read the element one
     * octet short, was left with a stray one, and answered with no records — which the stack reports as "no
     * iAP2 service on this iPhone". The peer here answers that way too, so this fails on it.
     */
    @Test fun theSearchPatternIsWellFormedAndNamesTheIap2Service() {
        val peer = Peer()
        val client = SdpClient(peer.engine)
        peer.onData = client::onData

        assertEquals(3, client.findRfcommChannel(handle = 0x0B))

        assertArrayEquals(
            SdpCodec.uuid128(ActionsBluetooth.IAP2_UUID_128),
            peer.searchedUuid,
        )
    }

    /**
     * A question the phone sends us arrives on the same channel as its answer to our query. Queued as an answer
     * it parses as "our query failed", which reads as the phone refusing us and sends the next investigation to
     * the wrong end of the link. The channel still has to come back from the real answer behind it.
     */
    /**
     * The phone asks who this accessory is the moment it connects, and drops the link when nothing answers.
     * These are the bytes that answer it: one sequence of attribute lists holding the record's own sequence,
     * which names the accessory-side UUID in SDP's byte order and publishes RFCOMM channel 1.
     */
    @Test fun thePhonesQuestionIsAnsweredWithTheAccessoryRecord() {
        val peer = Peer()
        peer.channel = 5
        peer.sendsARequestFirst = true
        val client = SdpClient(peer.engine, { })
        peer.onData = client::onData

        assertEquals(5, client.findRfcommChannel(handle = 0x0B))

        val answer = peer.sent.map { L2capCodec.pduPayload(it) }
            .firstOrNull { it.firstOrNull()?.toInt()?.and(0xFF) == SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE }
        assertNotNull("the phone's question must be answered, not queued as the reply to ours", answer)
        assertArrayEquals(peer.accessoryRecordAnswer(), answer)
    }

    /** A question nothing can be read out of is said so, not answered with a record nobody asked for. */
    @Test fun aRequestThatDoesNotParseIsReportedInsteadOfAnswered() {
        val peer = Peer()
        val diagnostics = CopyOnWriteArrayList<String>()
        val client = SdpClient(peer.engine, diagnostics::add)

        client.onData(cid = 0x0041, payload = peer.requestWithoutParameters())

        assertTrue(diagnostics.any { it.contains("did not parse") })
    }

    /**
     * Both directions share this one channel, so the half that carries a reply to our own query is evidence
     * too. It used to be queued without a word, which left a record the phone never sent and a record that
     * never came back reading the same way.
     */
    @Test fun aReplyToOurOwnQueryIsReportedRatherThanQueuedInSilence() {
        val peer = Peer()
        val diagnostics = CopyOnWriteArrayList<String>()
        val client = SdpClient(peer.engine, diagnostics::add)

        client.onData(
            cid = 0x0041,
            payload = byteArrayOf(SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE.toByte(), 0x00, 0x01, 0x00, 0x00),
        )

        assertTrue(
            "a reply of the phone's must be visible as a reply",
            diagnostics.any { it.contains("sdp response") && it.contains("pdu=0x7") },
        )
    }

    @Test fun anEmptyPduIsReportedRatherThanDroppedInSilence() {
        val peer = Peer()
        val diagnostics = CopyOnWriteArrayList<String>()
        val client = SdpClient(peer.engine, diagnostics::add)

        client.onData(cid = 0x0041, payload = ByteArray(0))

        assertTrue(diagnostics.any { it.contains("empty SDP PDU") })
    }
}
