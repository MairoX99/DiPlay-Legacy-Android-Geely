package com.shilapi.xcertplay.transport.hci

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Answers the iPhone's SDP questions and asks the iPhone where its iAP2 service lives.
 *
 * Both directions run on one L2CAP channel, so [onData] has to tell them apart: a question about our own
 * records is answered from here, and a reply to a query of ours is queued for [findRfcommChannel].
 */
internal class SdpClient(
    private val engine: L2capEngine,
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val responses = LinkedBlockingQueue<ByteArray>()

    /**
     * The engine's data sink routes this channel's PDUs here.
     *
     * A reply to a query of ours belongs in the queue. A PDU the phone sends the other way is a question about
     * *our* records, and queuing it would parse as "our query failed", pointing the next investigation at the
     * wrong end of the link — SDP numbers every response one above its request, so the low bit separates them.
     */
    fun onData(cid: Int, payload: ByteArray) {
        val pduId = payload.firstOrNull()?.toInt()?.and(0xFF)
        if (pduId == null) {
            onDiagnostic("adapter-bt: an empty SDP PDU on cid=$cid")
            return
        }
        if (pduId and 1 == 0) {
            answerAccessoryQuery(cid, pduId, payload)
            return
        }
        // A reply to a query of ours, which used to be queued without a word. Both directions run on this one
        // channel, so leaving this half silent made a record the phone never asked for and an answer it never
        // sent read the same way.
        onDiagnostic("adapter-bt: sdp response pdu=0x${pduId.toString(16)} cid=$cid bytes=${payload.size}")
        responses += payload
    }

    /**
     * Answers the phone asking what this accessory is.
     *
     * The phone opens this channel as soon as it connects and drops the link when no answer comes — observed on
     * the car as a disconnect four seconds later with no pairing event in between — so this answer is what lets
     * pairing begin at all. It is written here, on the thread the question arrived on; the send is queued to the
     * controller's own writer, so nothing here waits on the ACL window this same thread has to release.
     */
    private fun answerAccessoryQuery(cid: Int, pduId: Int, payload: ByteArray) {
        if (pduId != SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST) {
            onDiagnostic("adapter-bt: sdp request pdu=0x${pduId.toString(16)}")
            return
        }
        val request = SdpCodec.parseSearchAttributeRequest(payload)
        if (request == null) {
            onDiagnostic("adapter-bt: dropped an SDP request that did not parse")
            return
        }
        engine.sendOnAcceptedChannel(
            cid,
            SdpCodec.accessoryServiceSearchAttributeResponse(
                transactionId = request.transactionId,
                maximumAttributeByteCount = request.maximumAttributeByteCount,
                continuation = request.continuation,
            ),
        )
        // The question goes in ahead of the answer: if the phone drops the link anyway, what it asked for is
        // the only thing that says whether the record was too thin or the answer never landed.
        onDiagnostic("adapter-bt: sdp asked (pattern max ids cont) ${request.question}")
        onDiagnostic("adapter-bt: answered accessory SDP")
    }

    /** Finds the RFCOMM channel of the iPhone's iAP2 service, or throws. */
    fun findRfcommChannel(handle: Int, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Int {
        val cid = engine.connect(handle, L2capCodec.PSM_SDP, timeoutMillis)
        var continuation = ByteArray(0)
        var transactionId = FIRST_TRANSACTION_ID

        repeat(MAX_ROUNDS) {
            engine.send(
                handle,
                cid,
                SdpCodec.serviceSearchAttributeRequest(
                    transactionId = transactionId++,
                    uuid = SdpCodec.uuid128(ActionsBluetooth.IAP2_UUID_128),
                    attributeIds = intArrayOf(SdpCodec.ATTR_PROTOCOL_DESCRIPTOR_LIST),
                    continuation = continuation,
                ),
            )
            val response = responses.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                ?: throw IOException("the iPhone did not answer the iAP2 SDP query within ${timeoutMillis}ms")
            val parsed = SdpCodec.parseSearchAttributeResponse(response)
            parsed.errorCode?.let { error ->
                onDiagnostic("adapter-bt: sdp query failed, error=0x${error.toString(16)}")
                throw IOException("the iPhone answered the iAP2 SDP query with error 0x${error.toString(16)}")
            }
            for (record in parsed.attributeLists) {
                val value = SdpCodec.attributeValue(record, SdpCodec.ATTR_PROTOCOL_DESCRIPTOR_LIST)
                val channel = value?.let(SdpCodec::rfcommChannelFromProtocolDescriptorList)
                if (channel != null) return channel
            }
            if (parsed.continuation.isEmpty()) {
                onDiagnostic(
                    "adapter-bt: sdp returned ${parsed.totalServiceRecords} records, none naming RFCOMM",
                )
                throw IOException("no iAP2 service on this iPhone")
            }
            continuation = parsed.continuation
        }
        throw IOException("the iPhone's iAP2 SDP response never finished")
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        const val FIRST_TRANSACTION_ID = 0x0001
        const val MAX_ROUNDS = 4
    }
}
