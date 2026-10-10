package com.shilapi.xcertplay.transport.hci

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One RFCOMM data channel on top of an L2CAP channel: the multiplexer handshake, parameter negotiation,
 * credit-based flow control, and the user data itself.
 *
 * Frames arrive on whichever thread feeds the engine's data ([onData]); [open] and [send] run on the caller's
 * thread and wait on a queue, so nothing here blocks the reader.
 */
/** What a byte stream needs of an open RFCOMM channel, so it can be driven by a fake in tests. */
internal interface RfcommDlc {
    fun send(handle: Int, dlci: Int, payload: ByteArray)
    fun close(handle: Int, dlci: Int)

    /**
     * Stops granting the phone more room, the byte stream's backpressure.
     *
     * It cannot be applied by waiting in the stream itself: whoever pushes bytes there is the adapter's one
     * HCI reader, and an ACL permit is released by an event only that thread reads. Holding the credits stops
     * the phone at the far end instead, and a phone that has spent what it holds simply stops sending.
     */
    fun holdCredits() {}

    /** Grants again, now: once the phone has stopped, no inbound frame is left to trigger a top-up. */
    fun releaseCredits() {}
}

internal class RfcommChannel(
    private val engine: L2capEngine,
    private val onData: (ByteArray) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
    private val onPeerEnded: () -> Unit = {},
) : RfcommDlc {
    /** How many frames the phone will still accept from us. */
    @Volatile var peerCredits: Int = 0
        private set

    private val lock = Any()
    private val inbound = LinkedBlockingQueue<RfcommFrame>()

    /** Credits we have granted the phone and it has not spent yet. */
    private var grantedCredits = CREDITS_GRANTED

    /** Set while the byte stream is behind and has asked us to stop letting the phone send. */
    @Volatile private var creditsHeld = false

    @Volatile var cid = -1
        private set

    /**
     * The DLC this channel opened, or -1.
     *
     * Not a constant, and not necessarily 2: it comes from the server channel the phone's own SDP record
     * named, because the CarPlay specification says the accessory "must not assume that the channel will
     * remain the same" between connections.
     */
    @Volatile var dlci = -1
        private set

    private var handle = -1

    /**
     * Opens the multiplexer, negotiates parameters and opens the data channel; returns its DLCI.
     *
     * [serverChannel] is the RFCOMM channel the phone advertised for its iAP2 service.
     */
    fun open(handle: Int, serverChannel: Int, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): Int {
        this.handle = handle
        dlci = rfcommDlciFor(serverChannel)
        // This channel outlives a session: a stream that ended while holding the credits, or a grant the
        // last session spent, must not carry into the next one. The negotiation below is what the phone
        // is told, so it is also what we must remember.
        creditsHeld = false
        grantedCredits = CREDITS_GRANTED
        cid = engine.connect(handle, L2capCodec.PSM_RFCOMM, timeoutMillis)

        send(muxFrame(RfcommFrameType.SABM, MUX_DLCI))
        awaitFrame(timeoutMillis, "the multiplexer was not acknowledged") {
            it.type == RfcommFrameType.UA && it.dlci == MUX_DLCI
        }

        send(
            muxFrame(
                RfcommFrameType.MCC,
                MUX_DLCI,
                mccType = RfcommFrameCodec.MCC_PN,
                information = RfcommFrameCodec.parameterNegotiation(dlci, FRAME_SIZE, CREDITS_GRANTED, command = true),
            ),
        )
        val negotiation = awaitFrame(timeoutMillis, "the phone did not answer parameter negotiation") {
            it.type == RfcommFrameType.MCC && it.mccType == RfcommFrameCodec.MCC_PN
        }
        peerCredits = negotiation.information.getOrNull(7)?.toInt()?.and(0xFF) ?: 0

        // iOS is not known to tolerate a data channel that opens without one.
        send(
            muxFrame(
                RfcommFrameType.MCC,
                MUX_DLCI,
                mccType = RfcommFrameCodec.MCC_MSC,
                information = byteArrayOf(((dlci shl 2) or 0x03).toByte(), MODEM_STATUS_READY),
            ),
        )
        send(muxFrame(RfcommFrameType.SABM, dlci))
        awaitFrame(timeoutMillis, "the data channel was not acknowledged") {
            it.type == RfcommFrameType.UA && it.dlci == dlci
        }
        return dlci
    }

    /** Sends user data, spending one of the phone's credits. Throws rather than dropping it silently. */
    override fun send(handle: Int, dlci: Int, payload: ByteArray) {
        synchronized(lock) {
            if (peerCredits <= 0) throw IOException("no RFCOMM credits left from the iPhone")
            peerCredits -= 1
        }
        send(
            RfcommFrame(
                dlci = dlci,
                type = RfcommFrameType.UIH,
                command = true,
                pollFinal = false,
                credits = null,
                mccType = 0,
                information = payload,
            ),
        )
    }

    override fun close(handle: Int, dlci: Int) {
        send(muxFrame(RfcommFrameType.DISC, dlci))
        engine.disconnect(cid)
    }

    override fun holdCredits() {
        creditsHeld = true
    }

    override fun releaseCredits() {
        creditsHeld = false
        // The phone has spent what it held and stopped, so the next inbound frame that would normally carry
        // the top-up is never coming. Sending it from here is the whole point of the release.
        try {
            topUpThePhoneIfItIsRunningOut()
        } catch (error: Exception) {
            onDiagnostic("adapter-bt: could not release RFCOMM credits: ${error.javaClass.simpleName}")
        }
    }

    /** The engine's data sink routes the RFCOMM channel here. */
    fun onData(cid: Int, payload: ByteArray) {
        // A channel the phone dialled never went through [open], so this is where the reply path learns which
        // L2CAP channel to answer on. The outbound path already knows it, and the value is the same.
        if (handle < 0) this.cid = cid
        val frame = RfcommFrameCodec.decode(payload)
        if (frame == null) {
            onDiagnostic("adapter-bt: dropped a malformed RFCOMM frame")
            return
        }
        when (frame.type) {
            RfcommFrameType.SABM -> send(frame.copy(type = RfcommFrameType.UA, command = false))
            RfcommFrameType.DISC -> {
                send(frame.copy(type = RfcommFrameType.UA, command = false))
                onDiagnostic("adapter-bt: the iPhone closed the RFCOMM channel")
                onPeerEnded()
            }
            RfcommFrameType.UA, RfcommFrameType.DM -> inbound += frame
            RfcommFrameType.MCC -> when {
                // A modem-status command has to be answered, and the answer carries the same status back. The
                // phone's arrives after ours has gone out, so leaving it unanswered leaves the phone holding a
                // channel it believes is coming up, with nothing on the wire saying why no data ever follows.
                frame.mccType == RfcommFrameCodec.MCC_MSC && frame.command ->
                    send(frame.copy(command = false))

                frame.mccType != RfcommFrameCodec.MCC_PN -> Unit

                // A PN *command* is the phone opening a DLC on the channel our SDP record named for it. A PN
                // response is the phone answering the negotiation [open] sent, which is what awaitFrame is
                // waiting for — answering that one would be answering our own question.
                frame.command -> acceptInboundDlc(frame)

                else -> inbound += frame
            }
            RfcommFrameType.UIH -> {
                frame.credits?.let { granted -> synchronized(lock) { peerCredits += granted } }
                if (frame.information.isNotEmpty()) {
                    // This frame spent one of the credits we granted the phone.
                    synchronized(lock) { grantedCredits -= 1 }
                    onData(frame.information)
                }
                // Any inbound UIH is a chance to give the phone more room — including a credit-only frame, which is
                // the only kind that can still arrive once it has spent the last data frame our grant allowed.
                topUpThePhoneIfItIsRunningOut()
            }
            RfcommFrameType.UNKNOWN -> onDiagnostic("adapter-bt: dropped an unknown RFCOMM frame")
        }
    }

    /**
     * Accepts a DLC the phone dialled, by answering the parameter negotiation it opened with.
     *
     * The iAP2 session in this stack is opened by dialling the phone, but the phone reads the accessory-side
     * record as an invitation and dials the channel our SDP answer named. Every frame of that side of the
     * conversation arrives with no handle of ours on it, so the answer goes out through the engine on the
     * channel it was accepted on.
     *
     * Leaving the negotiation unanswered is invisible: the phone has a channel it believes is coming up, no
     * data ever flows, and the link is dropped with nothing on the wire naming the reason.
     */
    private fun acceptInboundDlc(negotiation: RfcommFrame) {
        val peerDlci = negotiation.information.getOrNull(0)?.toInt()?.and(0x3F) ?: return
        dlci = peerDlci
        // The phone's own grant is what it is willing to receive from us; ours is what we are willing to take.
        grantedCredits = CREDITS_GRANTED
        creditsHeld = false
        peerCredits = negotiation.information.getOrNull(7)?.toInt()?.and(0xFF) ?: 0
        send(
            muxFrame(
                RfcommFrameType.MCC,
                MUX_DLCI,
                mccType = RfcommFrameCodec.MCC_PN,
                information = RfcommFrameCodec.parameterNegotiation(
                    peerDlci,
                    FRAME_SIZE,
                    CREDITS_GRANTED,
                    command = false,
                ),
                command = false,
            ),
        )
        // iOS is not known to tolerate a data channel that opens without one.
        send(
            muxFrame(
                RfcommFrameType.MCC,
                MUX_DLCI,
                mccType = RfcommFrameCodec.MCC_MSC,
                information = byteArrayOf(((peerDlci shl 2) or 0x03).toByte(), MODEM_STATUS_READY),
            ),
        )
        onDiagnostic("adapter-bt: the iPhone dialled RFCOMM DLCI $peerDlci on the channel SDP named")
    }

    /**
     * Gives the phone more room once it has spent most of what we granted.
     *
     * Credit flow control runs both ways: we granted credits in the negotiation, and the phone stops sending
     * when it has used them up. Without a top-up a working link looks exactly like a dead phone. The check runs on
     * every inbound UIH rather than only the data-carrying ones: a credit-only frame is how the phone tells us it
     * has room for us again, and it can arrive after the last data frame our own grant allowed — the point at which
     * there is no data frame left to trigger anything.
     */
    private fun topUpThePhoneIfItIsRunningOut() {
        val topUp = synchronized(lock) {
            // The consumer is behind and asked for the phone to be held. Withholding the grant *is* the
            // backpressure: the phone stops once it has spent what it holds, and nothing is dropped.
            if (creditsHeld) return
            if (grantedCredits > CREDIT_TOP_UP_AT) return
            // A top-up is itself a UIH frame and spends one of the phone's credits; if it owes us the room,
            // wait for the next frame that brings some rather than taking the last one for a bookkeeping frame.
            if (peerCredits <= 0) return
            peerCredits -= 1
            grantedCredits += CREDITS_GRANTED
            CREDITS_GRANTED
        }
        send(
            RfcommFrame(
                dlci = dlci,
                type = RfcommFrameType.UIH,
                command = true,
                // The credit octet only exists on a UIH frame whose poll/final bit is set.
                pollFinal = true,
                credits = topUp,
                mccType = 0,
                information = ByteArray(0),
            ),
        )
    }

    private fun awaitFrame(timeoutMillis: Long, failure: String, matches: (RfcommFrame) -> Boolean): RfcommFrame {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val frame = inbound.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            if (matches(frame)) return frame
            // A DM on our own DLC is the phone refusing it outright — the DLCI is wrong, or the service is
            // gone. Waiting for the deadline instead turned that into a ten-second hang with nothing said
            // about the DLCI being the reason.
            if (frame.type == RfcommFrameType.DM && frame.dlci == dlci) {
                throw IOException("$failure: the phone refused DLCI $dlci")
            }
        }
        onDiagnostic("adapter-bt: $failure")
        throw IOException(failure)
    }

    private fun muxFrame(
        type: RfcommFrameType,
        dlci: Int,
        mccType: Int = 0,
        information: ByteArray = ByteArray(0),
        command: Boolean = true,
    ): RfcommFrame = RfcommFrame(
        dlci = dlci,
        type = type,
        command = command,
        pollFinal = true,
        credits = null,
        mccType = mccType,
        information = information,
    )

    private fun send(frame: RfcommFrame) {
        val bytes = RfcommFrameCodec.encode(frame)
        // A channel the phone dialled has no handle of ours to send on; the engine remembers which ACL link it
        // was accepted over. Sending on the -1 this class was never given for that case would address a channel
        // that does not exist, which is the same as not answering at all.
        if (handle >= 0) engine.send(handle, cid, bytes) else engine.sendOnAcceptedChannel(cid, bytes)
    }

    private companion object {
        const val MUX_DLCI = 0
        const val FRAME_SIZE = 672
        const val CREDITS_GRANTED = 7

        /** Top up once this few of the granted credits are left. */
        const val CREDIT_TOP_UP_AT = 3
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L

        /** DV, RTR and RTC set: the modem-status frame iOS expects before data. */
        const val MODEM_STATUS_READY = 0x8D.toByte()
    }
}

/**
 * The DLCI of a channel to the other device's RFCOMM server channel [serverChannel].
 *
 * We send the first SABM on DLCI 0, so we are the initiator of the RFCOMM session, and RFCOMM splits the
 * DLCI space by session role: the server applications of the responder — here, everything on the iPhone —
 * are reachable at 2, 4, 6, …, which is the server channel shifted left. DLCI 2 was hardcoded here, which
 * is only correct while the phone happens to advertise channel 1.
 */
internal fun rfcommDlciFor(serverChannel: Int): Int = (serverChannel and 0x1F) shl 1
