package com.shilapi.xcertplay.transport.hci

import java.io.IOException

/** Where the L2CAP engine's bytes go: an ACL packet at a time, tagged with its packet boundary. */
internal interface AclSender {
    /** How many bytes of an L2CAP PDU one ACL packet can carry. */
    val aclPayloadBytes: Int

    fun sendAclPayload(handle: Int, packetBoundary: Int, payload: ByteArray)
}

/**
 * The connection-oriented L2CAP channels this stack needs: the signaling channel, and one channel per PSM.
 *
 * Only one ACL link is live at a time, so channels are keyed by their local channel id and remember the handle
 * they were opened on. [handleAcl] reassembles inbound PDUs across ACL fragments and runs on the reader thread,
 * so it never blocks; [connect] polls from the caller's thread instead.
 */
internal class L2capEngine(
    private val sender: AclSender,
    private val onData: (cid: Int, psm: Int, data: ByteArray) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
) {
    val localMtu: Int = DEFAULT_MTU

    private val lock = Any()
    private val channels = LinkedHashMap<Int, Channel>()
    private var nextCid = FIRST_DYNAMIC_CID
    private var nextIdentifier = 1
    private var inboundBuffer = ByteArray(0)

    /** Channels that have carried at least one inbound PDU, so the first one is reported and the rest are not. */
    private val dataStarted = HashSet<Int>()

    /** Opens and configures a channel to [psm]; returns our channel id, or throws if the peer never finishes. */
    fun connect(handle: Int, psm: Int, timeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS): Int {
        val channel = synchronized(lock) {
            Channel(localCid = nextCid++, psm = psm, handle = handle).also { channels[it.localCid] = it }
        }
        sendSignaling(
            handle,
            L2capCodec.CODE_CONNECTION_REQUEST,
            L2capCodec.connectionRequest(psm, channel.localCid),
        )

        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (channel.open) return channel.localCid
            if (channel.peerCid >= 0 && !channel.configurationSent) {
                sendConfigurationRequest(handle, channel)
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw IOException("L2CAP channel to PSM 0x${psm.toString(16)} was not configured within ${timeoutMillis}ms")
    }

    /**
     * Proposes this end's parameters, and says what was proposed.
     *
     * The line matters as much as the answer to it: a Configure Response that refuses a parameter names the one
     * to change, and a log carrying only the refusal leaves the value that caused it nowhere.
     */
    private fun sendConfigurationRequest(handle: Int, channel: Channel) {
        channel.configurationSent = true
        onDiagnostic(
            "adapter-bt: l2cap configure-request sent cid=${channel.localCid} " +
                "peer=0x${channel.peerCid.toString(16)} mtu=$localMtu",
        )
        sendSignaling(
            handle,
            L2capCodec.CODE_CONFIGURE_REQUEST,
            halfWord(channel.peerCid) + halfWord(0) + L2capCodec.configurationOptionMtu(localMtu),
        )
    }

    /**
     * Sends [payload] on the channel [cid], which is a **local** channel id; the PDU goes out addressed to the
     * peer's id for that channel, since that is what the receiver looks up.
     */
    fun send(handle: Int, cid: Int, payload: ByteArray) {
        val destination = synchronized(lock) { channels[cid]?.peerCid } ?: cid
        sendPdu(handle, L2capCodec.pdu(destination, payload))
    }

    /**
     * Sends [payload] on a channel this engine accepted from the phone, on the handle it was opened on.
     *
     * An answer to a question the phone asked has no handle of its own to pass: only one ACL link is live,
     * and a channel this engine accepted already remembers which one it is.
     */
    fun sendOnAcceptedChannel(cid: Int, payload: ByteArray) {
        val channel = synchronized(lock) { channels[cid] } ?: return
        send(channel.handle, cid, payload)
    }

    fun disconnect(cid: Int) {
        val channel = synchronized(lock) { channels.remove(cid) } ?: return
        if (channel.peerCid < 0) return
        sendSignaling(
            channel.handle,
            L2capCodec.CODE_DISCONNECTION_REQUEST,
            halfWord(channel.peerCid) + halfWord(channel.localCid),
        )
    }

    fun handleAcl(data: HciAclData) {
        val complete = synchronized(lock) { accumulate(data) }
        complete.forEach { dispatch(it, data.handle) }
    }

    private fun accumulate(data: HciAclData): List<ByteArray> {
        if (data.packetBoundary == PACKET_BOUNDARY_CONTINUATION) {
            if (inboundBuffer.isEmpty()) {
                onDiagnostic("adapter-bt: l2cap continuation with no start; dropped")
                return emptyList()
            }
            inboundBuffer += data.payload
        } else {
            inboundBuffer = data.payload
        }

        val pdus = mutableListOf<ByteArray>()
        var offset = 0
        while (inboundBuffer.size - offset >= L2capCodec.HEADER_BYTES) {
            val total = L2capCodec.HEADER_BYTES + word(inboundBuffer, offset)
            if (inboundBuffer.size - offset < total) break
            pdus += inboundBuffer.copyOfRange(offset, offset + total)
            offset += total
        }
        if (offset > 0) inboundBuffer = inboundBuffer.copyOfRange(offset, inboundBuffer.size)
        return pdus
    }

    private fun dispatch(pdu: ByteArray, handle: Int) {
        val cid = L2capCodec.pduCid(pdu)
        val payload = L2capCodec.pduPayload(pdu)
        if (cid == L2capCodec.SIGNALING_CID) {
            handleSignaling(payload, handle)
        } else {
            // The PSM goes out with the data, because the sink cannot work it out for itself: a channel the
            // peer dialled never answered a request of ours, so the half of the stack that owns it has no id
            // of its own to compare against yet.
            val psm = synchronized(lock) { channels[cid]?.psm } ?: -1
            // The first PDU on a channel is the only line that says the phone ever used it. Everything above
            // this point can complete — both configure directions answered, the channel called open — and the
            // phone still put nothing on it, and in that case the disconnect that follows is four seconds of a
            // phone waiting for something this log could not name.
            if (synchronized(lock) { dataStarted.add(cid) }) {
                onDiagnostic(
                    "adapter-bt: first data from the iPhone on cid=$cid psm=0x${psm.toString(16)} " +
                        "bytes=${payload.size}",
                )
            }
            onData(cid, psm, payload)
        }
    }

    /** `type:length` per option, in the order they arrived; a value would only be a guess at what it means. */
    private fun optionSummary(options: List<Pair<Int, ByteArray>>): String =
        if (options.isEmpty()) {
            "none"
        } else {
            options.joinToString(",") { "0x${it.first.toString(16)}:${it.second.size}" }
        }

    private fun handleSignaling(payload: ByteArray, handle: Int) {
        if (payload.size < L2capCodec.SIGNALING_HEADER_BYTES) {
            onDiagnostic("adapter-bt: signaling frame shorter than its header; dropped")
            return
        }
        val code = L2capCodec.signalingCode(payload)
        val identifier = L2capCodec.signalingIdentifier(payload)
        val data = L2capCodec.signalingData(payload)

        when (code) {
            L2capCodec.CODE_CONNECTION_REQUEST -> {
                val psm = word(data, 0)
                val peerCid = word(data, 2)
                if (psm != L2capCodec.PSM_SDP && psm != L2capCodec.PSM_RFCOMM) {
                    onDiagnostic("adapter-bt: refusing L2CAP channel to unsupported PSM 0x${psm.toString(16)}")
                    reply(
                        handle,
                        identifier,
                        L2capCodec.CODE_CONNECTION_RESPONSE,
                        L2capCodec.connectionResponse(0, peerCid, RESULT_PSM_NOT_SUPPORTED, 0),
                    )
                    return
                }
                val localCid = synchronized(lock) {
                    nextCid++.also { channels[it] = Channel(it, psm, handle, peerCid = peerCid) }
                }
                // The two channel ids the response carries are named from the *responder's* own view, so ours goes
                // in the destination field and the phone's in the source field. Sending them the way round the
                // request wrote them (the phone's first) makes the phone fail to match the source it asked with,
                // and it drops the link about four seconds later without ever sending the query this answers.
                reply(
                    handle,
                    identifier,
                    L2capCodec.CODE_CONNECTION_RESPONSE,
                    L2capCodec.connectionResponse(localCid, peerCid, L2capCodec.CONNECTION_SUCCESS, 0),
                )
                // Only the peer opens a channel to us, so this line is the one that says the phone reached back:
                // an SDP channel means it is asking us for our service records, which nothing here publishes.
                val service = if (psm == L2capCodec.PSM_SDP) "SDP" else "RFCOMM"
                onDiagnostic(
                    "adapter-bt: the iPhone opened an L2CAP $service channel (psm=0x${psm.toString(16)} cid=$localCid)",
                )
                val channel = synchronized(lock) { channels[localCid] }
                if (channel != null && !channel.configurationSent) {
                    sendConfigurationRequest(handle, channel)
                }
            }

            L2capCodec.CODE_CONNECTION_RESPONSE -> {
                val peerCid = word(data, 0)
                val localCid = word(data, 2)
                val result = word(data, 4)
                val channel = synchronized(lock) { channels[localCid] }
                if (channel == null) {
                    onDiagnostic("adapter-bt: connection response for unknown channel 0x${localCid.toString(16)}")
                } else if (result != L2capCodec.CONNECTION_SUCCESS) {
                    onDiagnostic("adapter-bt: channel 0x${localCid.toString(16)} refused, result=0x${result.toString(16)}")
                } else {
                    channel.peerCid = peerCid
                }
            }

            L2capCodec.CODE_CONFIGURE_REQUEST -> {
                val destinationCid = word(data, 0)
                val peerOptions = L2capCodec.configurationOptions(data.copyOfRange(4, data.size))
                // A channel the phone has stopped configuring is where a phone that never got our answers waits,
                // and the connect handshake above is the last thing this layer used to report: from here to the
                // disconnect was a blank, so "it never sent its request" and "we never answered" looked alike.
                // The options go in as types and lengths, never as values: what the phone asks for is the thing
                // this end can neither log nor honour while it only knows how to answer "accepted".
                onDiagnostic(
                    "adapter-bt: l2cap configure-request id=$identifier cid=$destinationCid " +
                        "options=${optionSummary(peerOptions)}",
                )
                val peerMtu = peerOptions
                    .firstOrNull { it.first == L2capCodec.OPTION_MTU }
                    ?.second
                    ?.let { word(it, 0) }
                val channel = synchronized(lock) { channels[destinationCid] }
                if (channel == null) {
                    // The request names a channel this end has no record of, so there is no id of ours to answer
                    // with. Linux rejects this rather than replying (cmd_reject_invalid_cid), and an answer would
                    // have to invent a channel id and name it back.
                    onDiagnostic("adapter-bt: configure request for unknown channel 0x${destinationCid.toString(16)}")
                    reply(
                        handle,
                        identifier,
                        L2capCodec.CODE_COMMAND_REJECT,
                        L2capCodec.commandRejectInvalidCid(destinationCid, 0),
                    )
                    return
                }
                if (peerMtu != null) channel.peerMtu = peerMtu
                channel.remoteConfigured = true
                openIfBothConfigured(channel)
                // The first field of the answer is the *phone's* channel, not the one its request named. That
                // field is the peer's own endpoint seen from the far end, and both Linux's l2cap_parse_conf_req
                // (rsp->scid = chan->dcid) and BTstack's configure-request handler (channel->remote_cid) send
                // their peer's id there. Echoing the request's id instead — ours — answers for a channel the
                // phone does not hold, so its own side never counts as configured and it never sends the query
                // this channel exists for.
                reply(
                    handle,
                    identifier,
                    L2capCodec.CODE_CONFIGURE_RESPONSE,
                    halfWord(channel.peerCid) + halfWord(0) + halfWord(0),
                )
            }

            L2capCodec.CODE_CONFIGURE_RESPONSE -> {
                // scid(2) + flags(2) + result(2), the order Linux's l2cap_conf_rsp is packed in. Anything
                // shorter has no result to read, and Linux drops it (`cmd_len < sizeof(*rsp)`) rather than
                // guessing — guessing "success" is what let a channel be called open on no evidence at all.
                if (data.size < CONFIGURE_RESPONSE_BYTES) {
                    onDiagnostic(
                        "adapter-bt: configure response shorter than its header (${data.size} bytes); dropped",
                    )
                    return
                }
                val localCid = word(data, 0)
                val flags = word(data, 2)
                val result = word(data, 4)
                val returned = if (data.size > CONFIGURE_RESPONSE_BYTES) {
                    L2capCodec.configurationOptions(data.copyOfRange(CONFIGURE_RESPONSE_BYTES, data.size))
                } else {
                    emptyList()
                }
                onDiagnostic(
                    "adapter-bt: l2cap configure-response id=$identifier cid=$localCid " +
                        "result=0x${result.toString(16)} flags=0x${flags.toString(16)} " +
                        "options=${optionSummary(returned)}",
                )
                val channel = synchronized(lock) { channels[localCid] }
                if (channel == null) {
                    onDiagnostic("adapter-bt: configure response for unknown channel 0x${localCid.toString(16)}")
                } else if (result == L2capCodec.CONFIG_SUCCESS) {
                    channel.localConfigured = true
                    openIfBothConfigured(channel)
                } else {
                    // Anything but success means this end's configuration never finished. The phone refused a
                    // parameter (0x0001), named an option it does not know (0x0003) or put the answer off
                    // (0x0004), and every one of those carries the option that caused it — which is the one thing
                    // that says which parameter to change. Counting them all as success is what let a channel
                    // this end called open and the phone never putting a byte on it read the same way.
                    onDiagnostic(
                        "adapter-bt: configure not accepted on cid=$localCid result=0x${result.toString(16)}; " +
                            "the channel stays unconfigured",
                    )
                }
            }

            L2capCodec.CODE_DISCONNECTION_REQUEST -> {
                val destinationCid = word(data, 0)
                val sourceCid = word(data, 2)
                onDiagnostic("adapter-bt: l2cap disconnection-request id=$identifier cid=$destinationCid")
                // The channel goes here rather than after the reply, so a request naming one this end has
                // already dropped is answered with the reject Linux sends — both ids, in the order its
                // cmd_reject_invalid_cid(conn, ident, dcid, scid) writes them — instead of a response to a
                // channel that no longer exists.
                if (synchronized(lock) { channels.remove(destinationCid) } == null) {
                    reply(
                        handle,
                        identifier,
                        L2capCodec.CODE_COMMAND_REJECT,
                        L2capCodec.commandRejectInvalidCid(destinationCid, sourceCid),
                    )
                    return
                }
                reply(
                    handle,
                    identifier,
                    L2capCodec.CODE_DISCONNECTION_RESPONSE,
                    halfWord(destinationCid) + halfWord(sourceCid),
                )
            }

            L2capCodec.CODE_DISCONNECTION_RESPONSE -> {
                synchronized(lock) { channels.remove(word(data, 0)) }
            }

            L2capCodec.CODE_INFORMATION_REQUEST -> {
                val infoType = word(data, 0)
                val value = when (infoType) {
                    INFO_EXTENDED_FEATURES -> ByteArray(4)
                    // Bit n of the mask is channel id n, so bit 1 is the signaling channel. The mask is eight
                    // octets wide: Linux fills rsp->data[0] and zeroes the remaining seven, and BTstack sends an
                    // eight-byte map. Only bit 1 is set because the signaling channel is the only one here.
                    INFO_FIXED_CHANNELS -> byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0)
                    else -> {
                        onDiagnostic("adapter-bt: unsupported information request type 0x${infoType.toString(16)}")
                        ByteArray(0)
                    }
                }
                val result = if (value.isEmpty() && infoType != INFO_EXTENDED_FEATURES) 0x0001 else 0x0000
                reply(
                    handle,
                    identifier,
                    L2capCodec.CODE_INFORMATION_RESPONSE,
                    halfWord(infoType) + halfWord(result) + value,
                )
            }

            L2capCodec.CODE_COMMAND_REJECT -> {
                // A reject is never answered. Falling through to the unknown-command branch below used to send a
                // second reject back at the first, which the specification forbids outright — and the reason field
                // is the only place a peer says which of our commands it would not take.
                val cids = if (data.size >= 6) {
                    " cids=0x${word(data, 2).toString(16)}/0x${word(data, 4).toString(16)}"
                } else {
                    ""
                }
                onDiagnostic(
                    "adapter-bt: the iPhone rejected a command id=$identifier " +
                        "reason=0x${word(data, 0).toString(16)}$cids",
                )
            }

            else -> {
                onDiagnostic("adapter-bt: rejecting unknown signaling command 0x${code.toString(16)}")
                reply(
                    handle,
                    identifier,
                    L2capCodec.CODE_COMMAND_REJECT,
                    halfWord(L2capCodec.REASON_COMMAND_NOT_UNDERSTOOD),
                )
            }
        }
    }

    private fun openIfBothConfigured(channel: Channel) {
        if (channel.peerCid >= 0 && channel.localConfigured && channel.remoteConfigured && !channel.open) {
            channel.open = true
            // The one line that says the channel can carry data, and so the one that separates a phone waiting
            // for a record from a phone whose channel never finished opening.
            onDiagnostic(
                "adapter-bt: l2cap channel open cid=${channel.localCid} psm=0x${channel.psm.toString(16)} " +
                    "peer=0x${channel.peerCid.toString(16)}",
            )
        }
    }

    private fun reply(handle: Int, identifier: Int, code: Int, data: ByteArray) {
        sendPdu(handle, L2capCodec.pdu(L2capCodec.SIGNALING_CID, L2capCodec.signaling(code, identifier, data)))
    }

    private fun sendSignaling(handle: Int, code: Int, data: ByteArray) {
        reply(handle, synchronized(lock) { nextIdentifier++ }, code, data)
    }

    private fun sendPdu(handle: Int, pdu: ByteArray) {
        val chunk = sender.aclPayloadBytes.coerceAtLeast(1)
        var offset = 0
        var first = true
        while (offset < pdu.size) {
            val end = minOf(offset + chunk, pdu.size)
            sender.sendAclPayload(
                handle,
                if (first) PACKET_BOUNDARY_START else PACKET_BOUNDARY_CONTINUATION,
                pdu.copyOfRange(offset, end),
            )
            offset = end
            first = false
        }
    }

    private fun word(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun halfWord(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private class Channel(
        val localCid: Int,
        val psm: Int,
        val handle: Int,
        @Volatile var peerCid: Int = -1,
        @Volatile var peerMtu: Int = DEFAULT_MTU,
        @Volatile var localConfigured: Boolean = false,
        @Volatile var remoteConfigured: Boolean = false,
        @Volatile var configurationSent: Boolean = false,
        @Volatile var open: Boolean = false,
    )

    private companion object {
        const val FIRST_DYNAMIC_CID = 0x0040
        const val DEFAULT_MTU = 672
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 5L
        const val PACKET_BOUNDARY_START = 0
        const val PACKET_BOUNDARY_CONTINUATION = 1
        const val INFO_EXTENDED_FEATURES = 0x0002
        const val INFO_FIXED_CHANNELS = 0x0003
        const val RESULT_PSM_NOT_SUPPORTED = 0x0002

        /** A Configure Response: scid, flags and result, two octets each. */
        const val CONFIGURE_RESPONSE_BYTES = 6
    }
}
