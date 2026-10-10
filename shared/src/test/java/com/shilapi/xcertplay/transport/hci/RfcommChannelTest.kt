package com.shilapi.xcertplay.transport.hci

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfcommChannelTest {
    /**
     * The iPhone's half of an RFCOMM conversation: answers the L2CAP handshake, acknowledges SABM with UA,
     * answers PN with its own credits, and records the frames it receives.
     */
    private class Peer {
        var grantedCredits = 7

        val received = CopyOnWriteArrayList<RfcommFrame>()
        var onData: ((Int, ByteArray) -> Unit)? = null

        private var remoteCid = -1

        val engine: L2capEngine = L2capEngine(
            object : AclSender {
                override val aclPayloadBytes = 600
                override fun sendAclPayload(handle: Int, packetBoundary: Int, payload: ByteArray) {
                    answer(handle, payload)
                }
            },
            { cid, _, data -> onData?.invoke(cid, data) },
        )

        private fun answer(handle: Int, payload: ByteArray) {
            val cid = L2capCodec.pduCid(payload)
            if (cid != L2capCodec.SIGNALING_CID) {
                answerRfcomm(handle, L2capCodec.pduPayload(payload))
                return
            }
            val signaling = L2capCodec.pduPayload(payload)
            val identifier = L2capCodec.signalingIdentifier(signaling)
            val data = L2capCodec.signalingData(signaling)
            when (L2capCodec.signalingCode(signaling)) {
                L2capCodec.CODE_CONNECTION_REQUEST -> {
                    remoteCid = word(data, 2)
                    reply(handle, L2capCodec.CODE_CONNECTION_RESPONSE, identifier, byteArrayOf(0x41, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00))
                }
                L2capCodec.CODE_CONFIGURE_REQUEST -> {
                    reply(handle, L2capCodec.CODE_CONFIGURE_RESPONSE, identifier, byteArrayOf(remoteCid.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00))
                    reply(
                        handle,
                        L2capCodec.CODE_CONFIGURE_REQUEST,
                        9,
                        byteArrayOf(remoteCid.toByte(), 0x00, 0x00, 0x00) + L2capCodec.configurationOptionMtu(672),
                    )
                }
            }
        }

        private fun answerRfcomm(handle: Int, payload: ByteArray) {
            val frame = RfcommFrameCodec.decode(payload) ?: return
            received += frame
            when {
                // A server channel is one specific DLCI, and this is what the phone does with a SABM for a
                // DLC it is not listening on. Refusing anything but the right one is what makes the tests
                // below depend on the channel SDP named instead of on whatever the stack assumed.
                frame.type == RfcommFrameType.SABM && frame.dlci != MUX && frame.dlci != DLCI ->
                    send(handle, frame.copy(type = RfcommFrameType.DM, command = false))

                frame.type == RfcommFrameType.SABM ->
                    send(handle, frame.copy(type = RfcommFrameType.UA, command = false))

                frame.type == RfcommFrameType.MCC && frame.mccType == RfcommFrameCodec.MCC_PN -> {
                    val pn = frame.information.copyOf()
                    pn[pn.size - 1] = grantedCredits.toByte()
                    send(
                        handle,
                        frame.copy(
                            type = RfcommFrameType.MCC,
                            command = false,
                            mccType = RfcommFrameCodec.MCC_PN,
                            information = pn,
                        ),
                    )
                }
            }
        }

        /** Hands a frame from the phone to the stack under test. */
        fun deliver(frame: RfcommFrame, handle: Int = 0x0B) {
            if (remoteCid < 0) remoteCid = 0x0040
            engine.handleAcl(
                HciAclData(handle, 0, 0, L2capCodec.pdu(remoteCid, RfcommFrameCodec.encode(frame))),
            )
        }

        private fun send(handle: Int, frame: RfcommFrame) {
            deliver(frame, handle)
        }

        private fun reply(handle: Int, code: Int, identifier: Int, data: ByteArray) {
            deliver(
                handle,
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(code, identifier, data),
            )
        }

        private fun deliver(handle: Int, cid: Int, payload: ByteArray) {
            engine.handleAcl(HciAclData(handle, 0, 0, L2capCodec.pdu(cid, payload)))
        }

        private fun word(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    @Test fun openRunsTheMuxThenPnThenTheDataChannel() {
        val peer = Peer()
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData

        val dlci = channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)

        assertEquals(DLCI, dlci)
        assertTrue(peer.received.any { it.type == RfcommFrameType.SABM && it.dlci == 0 })
        assertTrue(
            peer.received.any { it.type == RfcommFrameType.MCC && it.mccType == RfcommFrameCodec.MCC_PN },
        )
        assertTrue(peer.received.any { it.type == RfcommFrameType.SABM && it.dlci == DLCI })
        // iOS is not known to tolerate a missing MSC, so it goes out before data.
        assertTrue(peer.received.any { it.type == RfcommFrameType.MCC && it.mccType == RfcommFrameCodec.MCC_MSC })
    }

    @Test fun creditsComeFromThePhonesOwnNegotiation() {
        val peer = Peer()
        peer.grantedCredits = 4
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData

        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)

        assertEquals(4, channel.peerCredits)
    }

    @Test fun sendFailsLoudlyWhenThePhoneGrantedNoCredits() {
        val peer = Peer()
        peer.grantedCredits = 0
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)

        val failure = runCatching { channel.send(handle = 0x0B, dlci = DLCI, payload = byteArrayOf(0x01)) }

        assertTrue(failure.exceptionOrNull() is IOException)
    }

    @Test fun eachSentFrameConsumesOneCredit() {
        val peer = Peer()
        peer.grantedCredits = 2
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)

        channel.send(handle = 0x0B, dlci = DLCI, payload = byteArrayOf(0x01))
        channel.send(handle = 0x0B, dlci = DLCI, payload = byteArrayOf(0x02))

        assertEquals(0, channel.peerCredits)
        assertTrue(runCatching { channel.send(handle = 0x0B, dlci = DLCI, payload = byteArrayOf(0x03)) }.isFailure)
    }

    @Test fun inboundUserDataReachesTheSink() {
        val peer = Peer()
        val received = CopyOnWriteArrayList<ByteArray>()
        val channel = RfcommChannel(peer.engine, { received += it })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)

        peer.deliver(
            RfcommFrame(
                dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = false,
                credits = null, mccType = 0, information = byteArrayOf(0x11, 0x22),
            ),
        )

        assertEquals(1, received.size)
        assertEquals(listOf<Byte>(0x11, 0x22), received[0].toList())
    }

    @Test fun aPollFinalFrameFromThePhoneGrantsUsCredits() {
        val peer = Peer()
        peer.grantedCredits = 0
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)

        peer.deliver(
            RfcommFrame(
                dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = true,
                credits = 5, mccType = 0, information = ByteArray(0),
            ),
        )

        assertEquals(5, channel.peerCredits)
    }

    @Test fun aMalformedFrameIsDroppedInsteadOfBreakingTheChannel() {
        val peer = Peer()
        val received = CopyOnWriteArrayList<ByteArray>()
        val channel = RfcommChannel(peer.engine, { received += it })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)

        channel.onData(0x0040, byteArrayOf(0x0B))

        assertEquals(0, received.size)
    }

    /**
     * Credit flow control runs both ways: the phone stops sending once it has spent the credits we granted, so
     * we have to give it more before it runs out. Without this a healthy link stalls like a dead phone.
     */
    @Test fun thePhoneIsGrantedMoreRoomBeforeItRunsOut() {
        val peer = Peer()
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)
        peer.received.clear()

        repeat(5) {
            peer.deliver(
                RfcommFrame(
                    dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = false,
                    credits = null, mccType = 0, information = byteArrayOf(0x01),
                ),
            )
        }

        val topUp = peer.received.firstOrNull { it.type == RfcommFrameType.UIH && it.credits != null }
        assertTrue("the phone must be granted more room before it runs out", topUp != null)
        assertTrue("a top-up carries no data", topUp!!.information.isEmpty())
    }

    /**
     * The top-up is our half of the flow control, and it must not depend on the phone sending *data* to trigger it.
     * A credit-only frame is exactly how the phone tells us it has room again — and it is the only frame left that
     * can, once our own granted credits are down to the mark and the phone has spent its last one on data.
     */
    @Test fun aCreditOnlyFrameFromThePhoneStillGrantsItMoreRoom() {
        val peer = Peer()
        // No credit from the phone means no top-up may go out yet: the bookkeeping frame itself costs one.
        peer.grantedCredits = 0
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)
        // Four data frames bring what we granted the phone down to the top-up mark, and every check so far skipped.
        repeat(4) {
            peer.deliver(
                RfcommFrame(
                    dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = false,
                    credits = null, mccType = 0, information = byteArrayOf(0x01),
                ),
            )
        }
        peer.received.clear()

        peer.deliver(
            RfcommFrame(
                dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = true,
                credits = 5, mccType = 0, information = ByteArray(0),
            ),
        )

        assertTrue(
            "a credit-only frame is the last chance to give the phone more room",
            peer.received.any { it.type == RfcommFrameType.UIH && it.credits != null },
        )
    }

    @Test fun noTopUpGoesOutWhileThePhoneStillHasRoom() {
        val peer = Peer()
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)
        peer.received.clear()

        peer.deliver(
            RfcommFrame(
                dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = false,
                credits = null, mccType = 0, information = byteArrayOf(0x01),
            ),
        )

        assertTrue(peer.received.none { it.type == RfcommFrameType.UIH && it.credits != null })
    }

    /** The phone's server channel shifted left: the phone is the session's responder, so its DLCIs are even. */
    @Test fun theDataDlciComesFromTheServerChannelAndNotFromAConstant() {
        assertEquals(2, rfcommDlciFor(1))
        assertEquals(6, rfcommDlciFor(3))
        assertEquals(60, rfcommDlciFor(30))
    }

    /**
     * The phone answers a SABM for a DLC it is not listening on with DM. Waiting for the deadline after that
     * turned a wrong channel into a ten-second hang that said nothing about the channel being why.
     */
    @Test fun aRefusedDataChannelFailsAtOnceInsteadOfAtTheDeadline() {
        val peer = Peer()
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData

        val startedAt = System.currentTimeMillis()
        // One past the channel the peer listens on, so its only answer is DM.
        val failure = runCatching {
            channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL + 1, timeoutMillis = 5_000)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue("the refusal must name the DLCI: ${failure!!.message}", failure.message!!.contains("refused"))
        assertTrue(
            "a DM must end the wait instead of the deadline",
            System.currentTimeMillis() - startedAt < 2_000,
        )
    }

    /**
     * The stream's backpressure. While it is behind it asks for the phone to be held, and the grant that would
     * normally go out must not: handing the phone more room is exactly what it must not have.
     */
    @Test fun heldCreditsStopTheTopUpThatWouldOtherwiseGoOut() {
        val peer = Peer()
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)
        channel.holdCredits()
        peer.received.clear()

        repeat(6) {
            peer.deliver(
                RfcommFrame(
                    dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = false,
                    credits = null, mccType = 0, information = byteArrayOf(0x01),
                ),
            )
        }

        assertTrue(
            "a held stream must not hand the phone more room",
            peer.received.none { it.type == RfcommFrameType.UIH && it.credits != null },
        )
    }

    /**
     * Once the phone has spent what it held it stops, so the inbound frame that would normally carry the grant
     * is never coming. The release has to send it, or a link that was merely behind would never restart.
     */
    @Test fun releasingTheCreditsGrantsThePhoneRoomWithoutAnotherFrame() {
        val peer = Peer()
        val channel = RfcommChannel(peer.engine, { })
        peer.onData = channel::onData
        channel.open(handle = 0x0B, serverChannel = SERVER_CHANNEL)
        channel.holdCredits()
        repeat(6) {
            peer.deliver(
                RfcommFrame(
                    dlci = DLCI, type = RfcommFrameType.UIH, command = false, pollFinal = false,
                    credits = null, mccType = 0, information = byteArrayOf(0x01),
                ),
            )
        }
        peer.received.clear()

        channel.releaseCredits()

        assertTrue(
            "the release is the last thing left that can give the phone room again",
            peer.received.any { it.type == RfcommFrameType.UIH && it.credits != null },
        )
    }

    /**
     * The phone reads the accessory-side SDP record as an invitation and dials the channel it named. Nothing on
     * that side of the conversation carries a handle of ours, so this test is also what says the answer leaves
     * on the channel the phone opened rather than on the -1 this class was never given for that case.
     */
    @Test fun theChannelThePhoneDialsIsAnswered() {
        val listener = Listener()
        listener.dialIn()
        listener.deliver(parameterNegotiation())

        assertTrue(
            "the negotiation must be answered, or the DLC never comes up",
            listener.frames.any { it.mccType == RfcommFrameCodec.MCC_PN && !it.command },
        )
        assertTrue(
            "iOS is not known to tolerate a data channel that opens without one",
            listener.frames.any { it.mccType == RfcommFrameCodec.MCC_MSC },
        )
        assertEquals(INBOUND_DLCI, listener.channel!!.dlci)
    }

    /**
     * The phone sends a modem-status command of its own and will not pass data until it is answered, the same
     * way it waits for ours. Dropping it left the channel half open with nothing on the wire saying why.
     */
    @Test fun thePhonesMscCommandIsAnswered() {
        val listener = Listener()
        listener.dialIn()
        listener.sent.clear()

        listener.deliver(
            RfcommFrame(
                // A modem-status command is one of the multiplexer's own frames, so it travels on DLCI 0 and
                // names the data channel it is about inside its two-octet payload.
                dlci = MUX, type = RfcommFrameType.MCC, command = true, pollFinal = false,
                credits = null, mccType = RfcommFrameCodec.MCC_MSC,
                information = byteArrayOf(((INBOUND_DLCI shl 2) or 0x03).toByte(), 0x8D.toByte()),
            ),
        )

        assertTrue(
            "a modem-status command has to be answered: ${listener.frames.map { it.mccType }}",
            listener.frames.any { it.mccType == RfcommFrameCodec.MCC_MSC && !it.command },
        )
    }

    /**
     * The other PN is the phone answering the negotiation [open] sent. Answering that one would be answering
     * our own question, and the two are told apart by direction alone.
     */
    @Test fun thePhonesAnswerToOurOwnNegotiationIsNotAnsweredBack() {
        val listener = Listener()
        listener.dialIn()
        listener.sent.clear()

        listener.deliver(parameterNegotiation(command = false))

        assertTrue(
            "a PN response is not a question: ${listener.frames.map { it.mccType }}",
            listener.frames.none { it.mccType == RfcommFrameCodec.MCC_PN },
        )
    }

    /**
     * The phone's half of the conversation for the case where *it* dialled us: it opens the L2CAP channel, then
     * negotiates a DLC on its own side, and nothing here was given a handle to answer on.
     */
    private class Listener {
        val sent = CopyOnWriteArrayList<ByteArray>()
        var channel: RfcommChannel? = null

        val engine: L2capEngine = L2capEngine(
            object : AclSender {
                override val aclPayloadBytes = 600
                override fun sendAclPayload(handle: Int, packetBoundary: Int, payload: ByteArray) {
                    sent += payload
                }
            },
            { cid, _, data -> channel?.onData(cid, data) },
        )

        /** The phone opens a channel to the RFCOMM PSM. The engine hands out its own local id in reply. */
        fun dialIn() {
            channel = RfcommChannel(engine, { })
            deliver(
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(
                    L2capCodec.CODE_CONNECTION_REQUEST,
                    1,
                    L2capCodec.connectionRequest(L2capCodec.PSM_RFCOMM, REMOTE_CID),
                ),
            )
        }

        fun deliver(frame: RfcommFrame) {
            deliver(LOCAL_CID, RfcommFrameCodec.encode(frame))
        }

        val frames: List<RfcommFrame>
            get() = sent.mapNotNull { RfcommFrameCodec.decode(L2capCodec.pduPayload(it)) }

        private fun deliver(cid: Int, payload: ByteArray) {
            engine.handleAcl(HciAclData(HANDLE, 0, 0, L2capCodec.pdu(cid, payload)))
        }
    }

    private companion object {
        /** The RFCOMM channel the phone's SDP record names; the DLCI below is what follows from it. */
        const val SERVER_CHANNEL = 3
        const val DLCI = 6
        const val MUX = 0

        const val HANDLE = 0x0B

        /** The phone's own channel id, as it names it in its connection request. */
        const val REMOTE_CID = 0x0040

        /** The id the engine assigns that channel locally — its first dynamic one, so the same number. */
        const val LOCAL_CID = 0x0040

        /** Our published server channel is 1, so a phone dialling it opens DLCI 2. */
        const val INBOUND_DLCI = 2
    }
}

/** The DLC the phone opens on us, described the way it does: a PN command on the control channel. */
private fun parameterNegotiation(command: Boolean = true) = RfcommFrame(
    dlci = 0,
    type = RfcommFrameType.MCC,
    command = command,
    pollFinal = false,
    credits = null,
    mccType = RfcommFrameCodec.MCC_PN,
    information = RfcommFrameCodec.parameterNegotiation(2, 672, 7, command = command),
)
