package com.shilapi.xcertplay.transport.hci

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L2capEngineTest {
    /** Records what went out, and lets a test feed the peer's replies back in. */
    private class RecordingSender(override val aclPayloadBytes: Int = 16) : AclSender {
        val sent = CopyOnWriteArrayList<Sent>()
        override fun sendAclPayload(handle: Int, packetBoundary: Int, payload: ByteArray) {
            sent += Sent(handle, packetBoundary, payload)
        }
    }

    private data class Sent(val handle: Int, val packetBoundary: Int, val payload: ByteArray)

    private fun acl(handle: Int, packetBoundary: Int, payload: ByteArray) =
        HciAclData(handle, packetBoundary, 0, payload)

    private fun feed(engine: L2capEngine, handle: Int, cid: Int, payload: ByteArray) {
        engine.handleAcl(acl(handle, 0, L2capCodec.pdu(cid, payload)))
    }

    private fun feedSignaling(engine: L2capEngine, handle: Int, code: Int, identifier: Int, data: ByteArray) {
        feed(engine, handle, L2capCodec.SIGNALING_CID, L2capCodec.signaling(code, identifier, data))
    }

    /** The signaling payload of the first frame sent with [code]. */
    private fun waitFor(sender: RecordingSender, code: Int): ByteArray {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            sender.sent.forEach { frame ->
                if (L2capCodec.pduCid(frame.payload) == L2capCodec.SIGNALING_CID) {
                    val data = L2capCodec.pduPayload(frame.payload)
                    if (L2capCodec.signalingCode(data) == code) return data
                }
            }
            Thread.sleep(10)
        }
        error("no signaling command with code $code was sent")
    }

    private fun halfWord(value: Int) =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    @Test fun connectNegotiatesAChannelWithAWellBehavedPeer() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })
        var cid = -1
        val worker = Thread { cid = engine.connect(handle = 0x0B, psm = L2capCodec.PSM_RFCOMM) }
        worker.isDaemon = true
        worker.start()

        val request = waitFor(sender, L2capCodec.CODE_CONNECTION_REQUEST)
        val requestData = L2capCodec.signalingData(request)
        assertEquals(L2capCodec.PSM_RFCOMM, requestData[0].toInt() and 0xFF)
        val ourCid = (requestData[2].toInt() and 0xFF) or ((requestData[3].toInt() and 0xFF) shl 8)
        val peerCid = 0x0041

        // The peer answers with its own channel id as the destination and ours as the source.
        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONNECTION_RESPONSE,
            L2capCodec.signalingIdentifier(request),
            byteArrayOf(peerCid.toByte(), 0x00, ourCid.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00),
        )

        val configure = waitFor(sender, L2capCodec.CODE_CONFIGURE_REQUEST)
        val configureData = L2capCodec.signalingData(configure)
        assertEquals(peerCid, (configureData[0].toInt() and 0xFF) or ((configureData[1].toInt() and 0xFF) shl 8))
        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONFIGURE_RESPONSE,
            L2capCodec.signalingIdentifier(configure),
            byteArrayOf(ourCid.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00),
        )
        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONFIGURE_REQUEST,
            9,
            byteArrayOf(ourCid.toByte(), 0x00, 0x00, 0x00) + L2capCodec.configurationOptionMtu(1000),
        )

        worker.join(5_000)
        assertEquals(ourCid, cid)
    }

    @Test fun anSduLargerThanTheAclPayloadIsSplitIntoAStartThenContinuations() {
        val sender = RecordingSender(aclPayloadBytes = 16)
        val engine = L2capEngine(sender, { _, _, _ -> })

        engine.send(handle = 0x0B, cid = 0x0040, payload = ByteArray(40))

        assertEquals(listOf(0, 1, 1), sender.sent.map { it.packetBoundary })
        assertEquals(listOf(16, 16, 12), sender.sent.map { it.payload.size })
        assertEquals(0x0B, sender.sent.first().handle)
    }

    @Test fun inboundFragmentsAreReassembledBeforeTheyReachTheSink() {
        val sender = RecordingSender()
        val received = CopyOnWriteArrayList<ByteArray>()
        val engine = L2capEngine(sender, { _, _, data -> received += data })
        val sdu = L2capCodec.pdu(0x0040, ByteArray(30) { 0x33 })

        engine.handleAcl(acl(0x0B, 0, sdu.copyOfRange(0, 20)))
        assertEquals(0, received.size)
        engine.handleAcl(acl(0x0B, 1, sdu.copyOfRange(20, sdu.size)))

        assertEquals(1, received.size)
        assertArrayEquals(ByteArray(30) { 0x33 }, received[0])
    }

    @Test fun anInformationRequestForFixedChannelsIsAnsweredNotIgnored() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        feedSignaling(engine, 0x0B, L2capCodec.CODE_INFORMATION_REQUEST, 7, byteArrayOf(0x03, 0x00))

        val response = waitFor(sender, L2capCodec.CODE_INFORMATION_RESPONSE)
        assertEquals(L2capCodec.CODE_INFORMATION_RESPONSE, L2capCodec.signalingCode(response))
    }

    @Test fun anInboundConnectionRequestForRfcommIsAccepted() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONNECTION_REQUEST,
            3,
            L2capCodec.connectionRequest(L2capCodec.PSM_RFCOMM, 0x0041),
        )

        val response = L2capCodec.signalingData(waitFor(sender, L2capCodec.CODE_CONNECTION_RESPONSE))
        assertEquals(L2capCodec.CONNECTION_SUCCESS, response[4].toInt() and 0xFF)
        assertEquals(0x00, response[5].toInt() and 0xFF)
        // The response names the sender's own channel first and the requester's second — the reverse of the order
        // the request wrote them in. This engine's first dynamic channel is 0x0040; the phone asked with 0x0041.
        assertEquals(0x40, response[0].toInt() and 0xFF)
        assertEquals(0x00, response[1].toInt() and 0xFF)
        assertEquals(0x41, response[2].toInt() and 0xFF)
        assertEquals(0x00, response[3].toInt() and 0xFF)
    }

    /**
     * An SDP channel the phone opens is it asking for our service records. The log has to say so: nothing in
     * this stack publishes records, so nothing answers it, and the phone is left waiting with no other trace.
     */
    @Test fun anInboundSdpChannelIsAcceptedAndReported() {
        val sender = RecordingSender()
        val diagnostics = CopyOnWriteArrayList<String>()
        val engine = L2capEngine(sender, { _, _, _ -> }, diagnostics::add)

        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONNECTION_REQUEST,
            3,
            L2capCodec.connectionRequest(L2capCodec.PSM_SDP, 0x0041),
        )

        val response = L2capCodec.signalingData(waitFor(sender, L2capCodec.CODE_CONNECTION_RESPONSE))
        assertEquals(L2capCodec.CONNECTION_SUCCESS, response[4].toInt() and 0xFF)
        assertTrue(diagnostics.any { it.contains("L2CAP SDP channel") })
    }

    /**
     * A channel a phone opens carries nothing until both sides have configured it, and the phone sends the last
     * of those commands. A channel that stops before that point is a phone retrying a query nothing answers —
     * and that, a phone that never finished configuring and a phone whose configure answer never landed, was
     * exactly what this layer could not say.
     */
    @Test fun anInboundChannelThatFinishesConfiguringIsReportedAsOpen() {
        val sender = RecordingSender()
        val diagnostics = CopyOnWriteArrayList<String>()
        val engine = L2capEngine(sender, { _, _, _ -> }, diagnostics::add)

        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONNECTION_REQUEST,
            1,
            L2capCodec.connectionRequest(L2capCodec.PSM_SDP, 0x0041),
        )
        val response = L2capCodec.signalingData(waitFor(sender, L2capCodec.CODE_CONNECTION_RESPONSE))
        // The response names the sender's own channel first, so ours is the first field and the phone's the second.
        val ourCid = (response[0].toInt() and 0xFF) or ((response[1].toInt() and 0xFF) shl 8)
        assertTrue("the engine must name a channel of its own", ourCid > 0)

        // The phone accepts our MTU option, then asks us to configure its side.
        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONFIGURE_RESPONSE,
            2,
            halfWord(ourCid) + halfWord(0) + halfWord(0),
        )
        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONFIGURE_REQUEST,
            3,
            halfWord(ourCid) + halfWord(0) + L2capCodec.configurationOptionMtu(672),
        )

        assertTrue(
            "both configure commands must be readable",
            diagnostics.any { it.contains("l2cap configure-request") && it.contains("cid=$ourCid") } &&
                diagnostics.any { it.contains("l2cap configure-response") && it.contains("cid=$ourCid") },
        )
        assertTrue(
            "a channel both sides have configured must be reported as open",
            diagnostics.any { it.contains("l2cap channel open") && it.contains("cid=$ourCid") },
        )

        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_DISCONNECTION_REQUEST,
            4,
            halfWord(ourCid) + halfWord(0x0041),
        )
        assertTrue(
            "the phone giving the channel up must be readable too",
            diagnostics.any { it.contains("l2cap disconnection-request") && it.contains("cid=$ourCid") },
        )
    }

    /**
     * The answer to a phone configuring its own channel carries the *phone's* channel in its first field.
     *
     * That field is the endpoint at the far end, so every stack sends its peer's id there: Linux's
     * `l2cap_parse_conf_req` writes `rsp->scid = chan->dcid`, BTstack writes `channel->remote_cid`, and the
     * phone writes ours when it answers us. Replying with the id the request carried — our own — answers for a
     * channel the phone does not hold, so its side never counts as configured and the query this channel was
     * opened for is never sent. The bytes are pinned literally, not through the encoder that builds them.
     */
    @Test fun theAnswerToAConfigureRequestNamesThePhonesOwnChannel() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        // The phone dials SDP (0x0001) and calls its own endpoint 0x0042.
        feedSignaling(engine, 0x0B, 0x02, 1, byteArrayOf(0x01, 0x00, 0x42, 0x00))

        val connectionResponse = L2capCodec.signalingData(waitFor(sender, 0x03))
        val ourCid = (connectionResponse[0].toInt() and 0xFF) or ((connectionResponse[1].toInt() and 0xFF) shl 8)
        assertEquals(0x0042, (connectionResponse[2].toInt() and 0xFF) or ((connectionResponse[3].toInt() and 0xFF) shl 8))

        // The phone configures its side. The field it fills is the endpoint at *our* end, so it is our id.
        feedSignaling(engine, 0x0B, 0x04, 2, byteArrayOf(ourCid.toByte(), (ourCid shr 8).toByte(), 0x00, 0x00))

        assertArrayEquals(
            byteArrayOf(0x42, 0x00, 0x00, 0x00, 0x00, 0x00),
            L2capCodec.signalingData(waitFor(sender, 0x05)),
        )
    }

    /**
     * A Configure Request naming a channel this end never opened is rejected, not answered.
     *
     * The payload is the one Linux's `cmd_reject_invalid_cid` writes: reason 0x0002, then the id the request
     * carried, then zero — there is no channel at this end to put second. Answering instead would have to invent
     * an id for a channel that does not exist and name it back.
     */
    @Test fun aConfigureRequestForAnUnknownChannelIsRejected() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        feedSignaling(engine, 0x0B, 0x04, 9, byteArrayOf(0x77, 0x00, 0x00, 0x00))

        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x77, 0x00, 0x00, 0x00),
            L2capCodec.signalingData(waitFor(sender, 0x01)),
        )
    }

    /** A disconnection for a channel already gone carries both ids, in the order the reject defines them. */
    @Test fun aDisconnectionRequestForAnUnknownChannelIsRejected() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        feedSignaling(engine, 0x0B, 0x06, 9, byteArrayOf(0x77, 0x00, 0x88.toByte(), 0x00))

        assertArrayEquals(
            byteArrayOf(0x02, 0x00, 0x77, 0x00, 0x88.toByte(), 0x00),
            L2capCodec.signalingData(waitFor(sender, 0x01)),
        )
    }

    /**
     * A channel's configuration is done when the phone says so, and the result field is where it says it.
     *
     * A Configure Response is scid(2) + flags(2) + result(2). Linux's `l2cap_config_rsp` treats 0x0001
     * (unacceptable parameters) and 0x0003 (unknown options) by re-negotiating from the options the peer sent
     * back, and 0x0004 (pending) by waiting for the answer that follows; only on 0x0000 does it let the channel
     * reach the connected state. Reading every answer as success is what made a channel this end called open and
     * a phone that was still waiting for its own side read exactly alike.
     */
    @Test fun aConfigureResponseThatIsNotASuccessLeavesTheChannelUnconfigured() {
        val sender = RecordingSender()
        val diagnostics = CopyOnWriteArrayList<String>()
        val engine = L2capEngine(sender, { _, _, _ -> }, diagnostics::add)

        // The phone dials SDP and calls its own endpoint 0x0042.
        feedSignaling(engine, 0x0B, 0x02, 1, byteArrayOf(0x01, 0x00, 0x42, 0x00))
        // Its Configure Request for our channel 0x0040, carrying MTU 672: type 0x01, length 2, A0 02 little-endian.
        feedSignaling(engine, 0x0B, 0x04, 2, byteArrayOf(0x40, 0x00, 0x00, 0x00, 0x01, 0x02, 0xA0.toByte(), 0x02))
        // Its answer to our request: result 0x0001, "unacceptable parameters".
        feedSignaling(engine, 0x0B, 0x05, 3, byteArrayOf(0x40, 0x00, 0x00, 0x00, 0x01, 0x00))

        assertTrue(
            "the result is the only thing that says what to change, so it has to reach the log",
            diagnostics.any { it.contains("l2cap configure-response") && it.contains("result=0x1") },
        )
        assertTrue(
            "a refused configuration must not be reported as an open channel",
            diagnostics.none { it.contains("l2cap channel open") },
        )

        // The same exchange answered with 0x0000 does open it, so the guard is the result and not the shape.
        feedSignaling(engine, 0x0B, 0x05, 4, byteArrayOf(0x40, 0x00, 0x00, 0x00, 0x00, 0x00))
        assertTrue(diagnostics.any { it.contains("l2cap channel open") && it.contains("cid=64") })
    }

    /** What the phone asks for goes into the log: this end answers every request the same way, and cannot say so. */
    @Test fun aConfigureRequestReportsTheOptionsItCarries() {
        val sender = RecordingSender()
        val diagnostics = CopyOnWriteArrayList<String>()
        val engine = L2capEngine(sender, { _, _, _ -> }, diagnostics::add)

        feedSignaling(engine, 0x0B, 0x02, 1, byteArrayOf(0x01, 0x00, 0x42, 0x00))
        feedSignaling(engine, 0x0B, 0x04, 2, byteArrayOf(0x40, 0x00, 0x00, 0x00, 0x01, 0x02, 0xA0.toByte(), 0x02))

        assertTrue(
            diagnostics.any { it.contains("l2cap configure-request") && it.contains("options=0x1:2") },
        )
    }

    /** A Configure Request with no options at all is the other shape, and says so rather than printing nothing. */
    @Test fun aConfigureRequestWithNoOptionsSaysSo() {
        val sender = RecordingSender()
        val diagnostics = CopyOnWriteArrayList<String>()
        val engine = L2capEngine(sender, { _, _, _ -> }, diagnostics::add)

        feedSignaling(engine, 0x0B, 0x02, 1, byteArrayOf(0x01, 0x00, 0x42, 0x00))
        feedSignaling(engine, 0x0B, 0x04, 2, byteArrayOf(0x40, 0x00, 0x00, 0x00))

        assertTrue(diagnostics.any { it.contains("l2cap configure-request") && it.contains("options=none") })
    }

    /**
     * The first PDU on a channel is the one that says the phone ever used it.
     *
     * Every layer above this can be complete — both configure directions answered, both sides' requests
     * accepted, the channel called open — and the phone still put nothing on it. Without this line that state has
     * no report of its own, and the disconnect that follows reads the same as a phone that was never answered.
     */
    @Test fun theFirstPduOnAChannelIsReportedAndTheRestAreNot() {
        val sender = RecordingSender()
        val diagnostics = CopyOnWriteArrayList<String>()
        val payloads = CopyOnWriteArrayList<Int>()
        val engine = L2capEngine(sender, { _, _, data -> payloads += data.size }, diagnostics::add)

        feedSignaling(engine, 0x0B, 0x02, 1, byteArrayOf(0x01, 0x00, 0x42, 0x00))
        feed(engine, 0x0B, 0x40, byteArrayOf(0x06, 0x01, 0x00))
        feed(engine, 0x0B, 0x40, byteArrayOf(0x06, 0x02, 0x00))

        assertEquals(listOf(3, 3), payloads.toList())
        assertEquals(
            "one line per channel, not one per PDU",
            1,
            diagnostics.count { it.contains("first data from the iPhone") },
        )
        assertTrue(diagnostics.any { it.contains("first data from the iPhone") && it.contains("psm=0x1") })
    }

    /**
     * A Command Reject is reported and never answered.
     *
     * It used to fall through to the unknown-command branch and be answered with a second reject, which the
     * specification forbids outright. The reason field is also the only place a peer says which of our commands
     * it would not take, so dropping it silently would hide the answer to "why is it not talking to us".
     */
    @Test fun aCommandRejectIsReportedAndNeverAnswered() {
        val sender = RecordingSender()
        val diagnostics = CopyOnWriteArrayList<String>()
        val engine = L2capEngine(sender, { _, _, _ -> }, diagnostics::add)

        // reason 0x0002 "invalid CID", then the two channel ids struct l2cap_cmd_rej_cid carries.
        feedSignaling(engine, 0x0B, 0x01, 9, byteArrayOf(0x02, 0x00, 0x40, 0x00, 0x77, 0x00))

        assertTrue(
            diagnostics.any { it.contains("the iPhone rejected a command") && it.contains("reason=0x2") },
        )
        assertTrue("a reject is never answered, least of all with another reject", sender.sent.isEmpty())
    }

    @Test fun anInboundConnectionRequestForAnUnknownPsmIsRefused() {        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        feedSignaling(
            engine,
            0x0B,
            L2capCodec.CODE_CONNECTION_REQUEST,
            3,
            L2capCodec.connectionRequest(0x0099, 0x0041),
        )

        val response = L2capCodec.signalingData(waitFor(sender, L2capCodec.CODE_CONNECTION_RESPONSE))
        // Result 0x0002 is "PSM not supported".
        assertEquals(0x02, response[4].toInt() and 0xFF)
        assertEquals(0x00, response[5].toInt() and 0xFF)
    }

    @Test fun anUnknownSignalingCommandIsRejectedNotIgnored() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        feedSignaling(engine, 0x0B, code = 0x7F, identifier = 5, data = ByteArray(0))

        val rejection = waitFor(sender, L2capCodec.CODE_COMMAND_REJECT)
        assertEquals(5, L2capCodec.signalingIdentifier(rejection))
        assertEquals(0x0000, L2capCodec.signalingData(rejection)[0].toInt() and 0xFF)
    }

    @Test fun aConnectThatIsNeverAnsweredGivesUpInsteadOfHanging() {
        val sender = RecordingSender()
        val engine = L2capEngine(sender, { _, _, _ -> })

        val failure = runCatching { engine.connect(handle = 0x0B, psm = L2capCodec.PSM_SDP, timeoutMillis = 200) }

        assertTrue(failure.exceptionOrNull() is java.io.IOException)
    }
}
