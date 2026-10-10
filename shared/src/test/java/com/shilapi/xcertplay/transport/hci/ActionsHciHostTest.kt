package com.shilapi.xcertplay.transport.hci

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole stack, offline: HCI framing, pairing, L2CAP, SDP, RFCOMM and the byte stream, driven by a fake
 * adapter that answers exactly as the real one would.
 */
class ActionsHciHostTest {
    @get:Rule val folder = TemporaryFolder()

    /**
     * The accessory-side iAP2 UUID as SDP's byte order spells it, `00000000-deca-fade-deca-deafdecacaff`.
     *
     * Written out rather than taken from [SdpCodec]: a test that reads the constant it is checking passes just
     * as well when that constant is the wrong three spellings of this UUID away from the one a phone looks for.
     */
    private val accessoryIap2UuidBytes = byteArrayOf(
        0x00, 0x00, 0x00, 0x00, 0xDE.toByte(), 0xCA.toByte(), 0xFA.toByte(), 0xDE.toByte(),
        0xDE.toByte(), 0xCA.toByte(), 0xDE.toByte(), 0xAF.toByte(), 0xDE.toByte(), 0xCA.toByte(),
        0xCA.toByte(), 0xFF.toByte(),
    )

    /** Plays both the adapter's firmware and the iPhone on the far end of the ACL link. */
    private class Adapter(val transport: FakeHciTransport) {
        var sdpChannel = 3

        /** The only DLCI this phone listens on: its server channel shifted left, as the spec has it. */
        val dlci: Int get() = sdpChannel shl 1

        val received = CopyOnWriteArrayList<ByteArray>()

        /** Every DLCI the mux and then the data channel were opened on, in order. */
        val sabmDlcis = CopyOnWriteArrayList<Int>()

        private val handle = HANDLE
        private var nextPhoneCid = 0x0041
        private val kind = mutableMapOf<Int, String>()
        private val hostCidOf = mutableMapOf<Int, Int>()
        private val phoneCidOf = mutableMapOf<Int, Int>()
        private var rfcommHostCid = -1

        init {
            transport.onCommand = ::onCommand
            transport.onAclWrite = ::onAclWrite
        }

        fun deliverToHost(data: ByteArray) {
            val frame = RfcommFrameCodec.encode(
                RfcommFrame(
                    dlci = dlci, type = RfcommFrameType.UIH, command = false, pollFinal = false,
                    credits = null, mccType = 0, information = data,
                ),
            )
            transport.enqueueAcl(aclFrame(handle, rfcommHostCid, frame))
        }

        private fun onCommand(packet: ByteArray) {
            val opcode = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
            when (opcode) {
                HCI_RESET -> transport.enqueueEvent(commandComplete(opcode, 0))
                READ_BD_ADDR ->
                    transport.enqueueEvent(commandComplete(opcode, 0, HciCommands.formatAddress(ADAPTER)))

                READ_LOCAL_VERSION -> transport.enqueueEvent(
                    commandComplete(opcode, 0, byteArrayOf(0x06, 0x00, 0x00, 0x0A, 0x0F, 0x00)),
                )

                READ_BUFFER_SIZE -> transport.enqueueEvent(
                    commandComplete(opcode, 0, byteArrayOf(0xFB.toByte(), 0x00, 0x1E, 0x08, 0x00, 0x00, 0x00)),
                )

                CREATE_CONNECTION -> {
                    // Create_Connection is acknowledged with a status; the handle arrives as its own event.
                    transport.enqueueEvent(commandStatus(opcode, 0))
                    transport.enqueueEvent(connectionCompleteEvent(handle, PHONE))
                }

                AUTHENTICATION_REQUESTED, SET_CONNECTION_ENCRYPTION -> {
                    transport.enqueueEvent(commandStatus(opcode, 0))
                    when (opcode) {
                        // Authentication is answered with its own event, and only then does the host ask for
                        // encryption: the real controller reports 0x06 before 0x0413 is ever sent.
                        AUTHENTICATION_REQUESTED -> transport.enqueueEvent(authenticationCompleteEvent(handle))
                        else -> transport.enqueueEvent(encryptionChangeEvent(handle, enabled = true))
                    }
                }

                else -> transport.enqueueEvent(commandComplete(opcode, 0))
            }
        }

        private fun onAclWrite(packet: ByteArray) {
            // Keep the adapter's ACL window open.
            transport.enqueueEvent(numberOfCompletedPackets(handle, 1))

            val cid = L2capCodec.pduCid(packet.copyOfRange(4, packet.size))
            val payload = L2capCodec.pduPayload(packet.copyOfRange(4, packet.size))
            if (cid == L2capCodec.SIGNALING_CID) {
                answerSignaling(payload)
            } else if (kind[cid] == SERVICE_SDP) {
                answerSdp(hostCidOf.getValue(cid))
            } else if (kind[cid] == SERVICE_RFCOMM) {
                answerRfcomm(payload, hostCidOf.getValue(cid))
            }
        }

        private fun answerSignaling(payload: ByteArray) {
            val code = L2capCodec.signalingCode(payload)
            val identifier = L2capCodec.signalingIdentifier(payload)
            val data = L2capCodec.signalingData(payload)
            when (code) {
                L2capCodec.CODE_CONNECTION_REQUEST -> {
                    val psm = word(data, 0)
                    val hostCid = word(data, 2)
                    val phoneCid = nextPhoneCid++
                    kind[phoneCid] = if (psm == L2capCodec.PSM_SDP) SERVICE_SDP else SERVICE_RFCOMM
                    hostCidOf[phoneCid] = hostCid
                    phoneCidOf[hostCid] = phoneCid
                    if (kind[phoneCid] == SERVICE_RFCOMM) rfcommHostCid = hostCid
                    replySignaling(
                        L2capCodec.CODE_CONNECTION_RESPONSE,
                        identifier,
                        byteArrayOf(phoneCid.toByte(), 0x00, hostCid.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00),
                    )
                }

                L2capCodec.CODE_CONFIGURE_REQUEST -> {
                    val phoneCid = word(data, 0)
                    val hostCid = hostCidOf[phoneCid] ?: return
                    replySignaling(
                        L2capCodec.CODE_CONFIGURE_RESPONSE,
                        identifier,
                        byteArrayOf(hostCid.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00),
                    )
                    replySignaling(
                        L2capCodec.CODE_CONFIGURE_REQUEST,
                        9,
                        byteArrayOf(hostCid.toByte(), 0x00, 0x00, 0x00) +
                            L2capCodec.configurationOptionMtu(672),
                    )
                }
            }
        }

        private fun answerSdp(hostCid: Int) {
            val pdl = byteArrayOf(0x35, 0x07, 0x35, 0x05, 0x19, 0x00, 0x03, 0x08, sdpChannel.toByte())
            val record = byteArrayOf(0x35, (3 + pdl.size).toByte(), 0x09, 0x00, 0x04) + pdl
            // The attribute lists are one sequence holding one sequence per matching record.
            val attributeLists = byteArrayOf(0x35, record.size.toByte()) + record
            val parameters = byteArrayOf(0x00, attributeLists.size.toByte()) + attributeLists + byteArrayOf(0x00)
            val response = byteArrayOf(0x07, 0x12, 0x34, 0x00, parameters.size.toByte()) + parameters
            transport.enqueueAcl(aclFrame(handle, hostCid, response))
        }

        private fun answerRfcomm(payload: ByteArray, hostCid: Int) {
            val frame = RfcommFrameCodec.decode(payload) ?: return
            if (frame.type == RfcommFrameType.SABM) sabmDlcis += frame.dlci
            when {
                // This phone listens on one DLCI, and answers a SABM for any other the way a real one does:
                // with DM. Refusing the rest is what pins the channel SDP named to the DLC that gets opened —
                // a fake that acknowledged every DLCI hid the two being out of step.
                frame.type == RfcommFrameType.SABM && frame.dlci != MUX_DLCI && frame.dlci != dlci ->
                    sendFrame(hostCid, frame.copy(type = RfcommFrameType.DM, command = false))

                frame.type == RfcommFrameType.SABM -> sendFrame(
                    hostCid,
                    frame.copy(type = RfcommFrameType.UA, command = false),
                )

                frame.type == RfcommFrameType.MCC && frame.mccType == RfcommFrameCodec.MCC_PN -> {
                    val pn = frame.information.copyOf()
                    pn[pn.size - 1] = PEER_CREDITS.toByte()
                    sendFrame(
                        hostCid,
                        frame.copy(
                            type = RfcommFrameType.MCC,
                            command = false,
                            mccType = RfcommFrameCodec.MCC_PN,
                            information = pn,
                        ),
                    )
                }

                frame.type == RfcommFrameType.UIH -> received += frame.information
            }
        }

        private fun sendFrame(hostCid: Int, frame: RfcommFrame) {
            transport.enqueueAcl(aclFrame(handle, hostCid, RfcommFrameCodec.encode(frame)))
        }

        private fun replySignaling(code: Int, identifier: Int, data: ByteArray) {
            transport.enqueueAcl(
                aclFrame(
                    handle,
                    L2capCodec.SIGNALING_CID,
                    L2capCodec.signaling(code, identifier, data),
                ),
            )
        }

        private fun word(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun host(transport: FakeHciTransport) =
        ActionsHciHost(transport, File(folder.root, "linkkeys.tsv"))

    private fun opcodeOf(packet: ByteArray): Int =
        (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)

    private fun scanCount(transport: FakeHciTransport): Int =
        transport.commands.count { opcodeOf(it) == 0x0C1A }

    private fun scanValue(transport: FakeHciTransport): Int =
        transport.commands.last { opcodeOf(it) == 0x0C1A }[3].toInt() and 0xFF

    private fun awaitPairing(host: ActionsHciHost): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (host.pairedTarget() != null) return true
            Thread.sleep(10)
        }
        return false
    }

    /** The signaling data of the first frame the host sent with [code], or null if it never did. */
    private fun signalingDataOf(transport: FakeHciTransport, code: Int): ByteArray? {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val found = transport.aclWrites.firstNotNullOfOrNull { packet ->
                val pdu = packet.copyOfRange(HciPackets.ACL_HEADER_BYTES, packet.size)
                if (L2capCodec.pduCid(pdu) != L2capCodec.SIGNALING_CID) return@firstNotNullOfOrNull null
                val payload = L2capCodec.pduPayload(pdu)
                if (L2capCodec.signalingCode(payload) == code) L2capCodec.signalingData(payload) else null
            }
            if (found != null) return found
            Thread.sleep(10)
        }
        return null
    }

    /**
     * The first SDP reply the host put on the air, with the channel it was addressed to.
     *
     * The channel matters as much as the bytes: an answer sent back on our own id reaches nothing, which is the
     * one mistake this direction can make and the one a check that only looked for the record would miss.
     */
    private fun sdpAnswer(transport: FakeHciTransport): Pair<Int, ByteArray>? {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val found = transport.aclWrites.firstNotNullOfOrNull { packet ->
                val pdu = packet.copyOfRange(HciPackets.ACL_HEADER_BYTES, packet.size)
                val cid = L2capCodec.pduCid(pdu)
                if (cid == L2capCodec.SIGNALING_CID) return@firstNotNullOfOrNull null
                val payload = L2capCodec.pduPayload(pdu)
                if (payload.firstOrNull()?.toInt()?.and(0xFF) == SdpCodec.PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE) {
                    cid to payload
                } else {
                    null
                }
            }
            if (found != null) return found
            Thread.sleep(10)
        }
        return null
    }

    private fun halfWord(value: Int) =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    /**
     * The one direction every other SDP test reached around: a request handed straight to [SdpClient] instead of
     * pushed in through the transport the car uses. The phone dials us, finishes configuring the channel it
     * opened, and asks for our records — the accessory record is what has to come back out through the radio.
     */
    @Test fun thePhonesOwnSdpQuestionIsAnsweredThroughTheWholeStack() {
        val transport = FakeHciTransport()
        Adapter(transport)
        val diagnostics = CopyOnWriteArrayList<String>()
        val host = ActionsHciHost(
            transport,
            File(folder.root, "linkkeys.tsv"),
            onDiagnostic = { line -> diagnostics += line },
        )
        assertTrue(host.start())

        // The phone opens an SDP channel of its own; no Create_Connection of ours is involved.
        val phoneCid = 0x0041
        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(
                    L2capCodec.CODE_CONNECTION_REQUEST,
                    1,
                    L2capCodec.connectionRequest(L2capCodec.PSM_SDP, phoneCid),
                ),
            ),
        )
        val connectionResponse = signalingDataOf(transport, L2capCodec.CODE_CONNECTION_RESPONSE)
        assertNotNull("the host must accept the channel the phone opened", connectionResponse)
        // The response names the responder's own channel first, the requester's second.
        val hostCid = (connectionResponse!![0].toInt() and 0xFF) or
            ((connectionResponse[1].toInt() and 0xFF) shl 8)

        // It answers our configure request, then asks us to configure its side — the last command before the
        // channel can carry anything.
        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(
                    L2capCodec.CODE_CONFIGURE_RESPONSE,
                    2,
                    halfWord(hostCid) + halfWord(0) + halfWord(0),
                ),
            ),
        )
        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(
                    L2capCodec.CODE_CONFIGURE_REQUEST,
                    3,
                    halfWord(hostCid) + halfWord(0) + L2capCodec.configurationOptionMtu(672),
                ),
            ),
        )

        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                hostCid,
                SdpCodec.serviceSearchAttributeRequest(
                    transactionId = 0x1234,
                    uuid = SdpCodec.uuid128(SdpCodec.ACCESSORY_IAP2_UUID_TEXT),
                    attributeIds = intArrayOf(SdpCodec.ATTR_PROTOCOL_DESCRIPTOR_LIST),
                    continuation = ByteArray(0),
                ),
            ),
        )

        val answer = sdpAnswer(transport)
        assertNotNull(
            "the phone's own question must be answered. Stack said:\n${diagnostics.joinToString("\n")}",
            answer,
        )
        // Addressed to the phone's own channel, and carrying the question's transaction back.
        assertEquals("the answer must go to the channel the phone opened", phoneCid, answer!!.first)
        val payload = answer.second
        assertEquals(0x07, payload[0].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(0x12, 0x34), payload.copyOfRange(1, 3))
        assertTrue(
            "the answer must carry the accessory-side iAP2 UUID",
            indexOf(payload, accessoryIap2UuidBytes) >= 0,
        )
        assertTrue(
            "the answer must publish RFCOMM channel 1",
            indexOf(payload, byteArrayOf(0x19, 0x00, 0x03, 0x08, 0x01)) >= 0,
        )
        host.close()
    }

    /**
     * The other direction of the same dial: the phone reads the accessory record, sees the RFCOMM channel that
     * record publishes, and opens that channel itself.
     *
     * Nothing about that channel has been through [RfcommChannel.open], so the RFCOMM half has no id of its
     * own for it at the moment its first frame arrives. Routing inbound data by comparing channel ids hands the
     * phone's multiplexer handshake to the SDP half, which files it as the answer to a query nobody made and
     * sends nothing back — leaving the phone waiting out its timer on a channel this stack published.
     */
    @Test fun thePhonesOwnRfcommChannelIsAnsweredThroughTheWholeStack() {
        val transport = FakeHciTransport()
        Adapter(transport)
        val diagnostics = CopyOnWriteArrayList<String>()
        val host = ActionsHciHost(
            transport,
            File(folder.root, "linkkeys.tsv"),
            onDiagnostic = { line -> diagnostics += line },
        )
        assertTrue(host.start())

        // The phone dials the RFCOMM PSM; no Create_Connection of ours is involved.
        val phoneCid = 0x0041
        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(
                    L2capCodec.CODE_CONNECTION_REQUEST,
                    1,
                    L2capCodec.connectionRequest(L2capCodec.PSM_RFCOMM, phoneCid),
                ),
            ),
        )
        val connectionResponse = signalingDataOf(transport, L2capCodec.CODE_CONNECTION_RESPONSE)
        assertNotNull("the host must accept the RFCOMM channel the phone opened", connectionResponse)
        val hostCid = (connectionResponse!![0].toInt() and 0xFF) or
            ((connectionResponse[1].toInt() and 0xFF) shl 8)

        // It answers our configure request, then asks us to configure its side — the last command before the
        // channel can carry anything.
        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(
                    L2capCodec.CODE_CONFIGURE_RESPONSE,
                    2,
                    halfWord(hostCid) + halfWord(0) + halfWord(0),
                ),
            ),
        )
        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                L2capCodec.SIGNALING_CID,
                L2capCodec.signaling(
                    L2capCodec.CODE_CONFIGURE_REQUEST,
                    3,
                    halfWord(hostCid) + halfWord(0) + L2capCodec.configurationOptionMtu(672),
                ),
            ),
        )

        // DLCI 0: every RFCOMM session starts with the multiplexer's own SABM.
        transport.enqueueAcl(
            aclFrame(
                HANDLE,
                hostCid,
                RfcommFrameCodec.encode(
                    RfcommFrame(
                        dlci = MUX_DLCI,
                        type = RfcommFrameType.SABM,
                        command = true,
                        pollFinal = true,
                        credits = null,
                        mccType = 0,
                        information = ByteArray(0),
                    ),
                ),
            ),
        )

        val ua = awaitRfcommFrame(transport) { it.type == RfcommFrameType.UA && it.dlci == MUX_DLCI }
        assertNotNull(
            "the phone's own mux handshake must be answered. Stack said:\n${diagnostics.joinToString("\n")}",
            ua,
        )
        // Addressed to the channel the phone opened, and a response rather than a command of our own.
        assertEquals("the answer must go to the channel the phone opened", phoneCid, ua!!.first)
        assertFalse("a UA answers a SABM; it does not ask one", ua.second.command)
        host.close()
    }

    /** Waits for an RFCOMM frame the host put on the air, with the channel it was addressed to. */
    private fun awaitRfcommFrame(
        transport: FakeHciTransport,
        matches: (RfcommFrame) -> Boolean,
    ): Pair<Int, RfcommFrame>? {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val found = transport.aclWrites.firstNotNullOfOrNull { packet ->
                val pdu = packet.copyOfRange(HciPackets.ACL_HEADER_BYTES, packet.size)
                val cid = L2capCodec.pduCid(pdu)
                if (cid == L2capCodec.SIGNALING_CID) return@firstNotNullOfOrNull null
                RfcommFrameCodec.decode(L2capCodec.pduPayload(pdu))?.takeIf(matches)?.let { cid to it }
            }
            if (found != null) return found
            Thread.sleep(10)
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        return -1
    }

    @Test fun startCanLeaveTheAdapterInvisibleUntilTheHotspotIsOn() {
        val transport = FakeHciTransport()
        Adapter(transport)
        val states = CopyOnWriteArrayList<Pair<AdapterBluetoothState, String>>()
        val host = ActionsHciHost(
            transport,
            File(folder.root, "keys.tsv"),
            onState = { state, reason -> states += state to reason },
        )

        assertTrue(host.start(discoverable = false))

        assertEquals(0, scanValue(transport))
        assertTrue(states.any { it.first == AdapterBluetoothState.UNADVERTISED && it.second == "hotspot-off" })
        assertTrue(states.none { it.first == AdapterBluetoothState.BROADCASTING })
        host.close()
    }

    @Test fun idleAfterASessionDisconnectsAndStaysQuietUntilDiscoveryResumes() {
        val transport = FakeHciTransport()
        Adapter(transport)
        val host = host(transport)
        assertTrue(host.start())
        transport.enqueueEvent(linkKeyNotificationEvent(PHONE, ByteArray(16) { 0x5A }, keyType = 5))
        assertTrue(awaitPairing(host))
        host.openStream().close()

        host.idleAfterSession()

        val disconnect = transport.commands.last {
            opcodeOf(it) == 0x0406
        }
        assertEquals(0x13, disconnect[disconnect.size - 1].toInt() and 0xFF)
        assertEquals(0, scanValue(transport))
        val scansWhileHeld = scanCount(transport)
        assertFalse(host.setDiscoverable(true))
        assertEquals(scansWhileHeld, scanCount(transport))

        host.resumeDiscovery()
        assertTrue(host.setDiscoverable(true))
        assertEquals(ActionsBluetooth.SCAN_ENABLE_DISCOVERABLE_CONNECTABLE, scanValue(transport))
        host.close()
    }

    @Test fun startResetsNamesAndReadsTheAdapterAddress() {
        val transport = FakeHciTransport()
        Adapter(transport)
        val host = host(transport)

        assertTrue(host.start())

        assertEquals(ADAPTER, host.localAddress)
        assertEquals(0x06, host.hciVersion)
        host.close()
    }

    @Test fun startFailsWhenTheAdapterRefusesToReset() {
        val transport = FakeHciTransport()
        transport.onCommand = { packet ->
            val opcode = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
            transport.enqueueEvent(commandComplete(opcode, if (opcode == HCI_RESET) 0x01 else 0x00))
        }
        val reasons = CopyOnWriteArrayList<String>()
        val host = ActionsHciHost(
            transport,
            File(folder.root, "keys.tsv"),
            onState = { state, reason ->
                if (state == AdapterBluetoothState.FAILED) reasons += reason
            },
        )

        assertFalse(host.start())

        assertEquals(listOf("hci-reset"), reasons.toList())
        host.close()
    }

    @Test fun openStreamWithoutAPairedPhoneSaysSoInsteadOfHanging() {
        val transport = FakeHciTransport()
        Adapter(transport)
        val states = CopyOnWriteArrayList<AdapterBluetoothState>()
        val host = ActionsHciHost(transport, File(folder.root, "keys.tsv"), { state, _ -> states += state })
        host.start()

        val failure = runCatching { host.openStream() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(states.contains(AdapterBluetoothState.NOT_PAIRED))
        host.close()
    }

    /** The core asset: the entire chain, with no USB involved. */
    @Test fun theWholeChainOpensAnIap2StreamBothWays() {
        val transport = FakeHciTransport()
        val adapter = Adapter(transport)
        adapter.sdpChannel = 3
        val host = host(transport)
        assertTrue(host.start())

        transport.enqueueEvent(linkKeyNotificationEvent(PHONE, ByteArray(16) { 0x5A }, keyType = 5))
        assertTrue("pairing should name the phone as the target", awaitPairing(host))
        assertEquals(PHONE, host.pairedTarget()!!.address)

        val stream = host.openStream()

        // The mux on DLCI 0, then the data DLC on the channel SDP named — 3, so 6.
        assertEquals(listOf(MUX_DLCI, 6), adapter.sabmDlcis.toList())

        stream.send(byteArrayOf(0x01, 0x02, 0x03))
        val deadline = System.currentTimeMillis() + 2_000
        while (adapter.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, adapter.received.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), adapter.received[0])

        adapter.deliverToHost(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), stream.recv(maxBytes = 16, timeoutMillis = 2_000))

        host.close()
    }

    /**
     * The channel is whatever SDP named, not a constant. The CarPlay specification is explicit that the
     * accessory "must not assume that the channel will remain the same" between connections, and this phone
     * has an iAP2 service on channel 5.
     */
    @Test fun theStreamOpensOnWhicheverChannelSdpNamed() {
        val transport = FakeHciTransport()
        val adapter = Adapter(transport)
        adapter.sdpChannel = 5
        val host = host(transport)
        assertTrue(host.start())
        transport.enqueueEvent(linkKeyNotificationEvent(PHONE, ByteArray(16) { 0x5A }, keyType = 5))
        assertTrue(awaitPairing(host))

        // A wrong DLCI is answered with DM, so reaching the data channel at all means it was the right one.
        val stream = host.openStream()
        assertEquals(listOf(MUX_DLCI, 10), adapter.sabmDlcis.toList())

        stream.send(byteArrayOf(0x07))
        val deadline = System.currentTimeMillis() + 2_000
        while (adapter.received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertArrayEquals(byteArrayOf(0x07), adapter.received[0])
        host.close()
    }

    @Test fun theLinkKeyThePhoneProducedIsKeptForNextTime() {
        val transport = FakeHciTransport()
        Adapter(transport)
        val host = host(transport)
        host.start()

        transport.enqueueEvent(linkKeyNotificationEvent(PHONE, ByteArray(16) { 0x5A }, keyType = 5))
        assertTrue(awaitPairing(host))
        host.close()

        // Written to the file the next run reads, not just held in memory.
        val stored = SspLinkStore(File(folder.root, "linkkeys.tsv")).key(PHONE)
        assertTrue(stored != null)
        assertEquals(0x5A, stored!!.linkKey[0].toInt() and 0xFF)
    }

    private companion object {
        const val ADAPTER = "F4:4E:FC:F8:8B:36"
        const val PHONE = "CC:60:23:D7:2F:5B"
        const val HANDLE = 0x0B
        const val PEER_CREDITS = 7
        const val MUX_DLCI = 0

        const val HCI_RESET = 0x0C03
        const val READ_BD_ADDR = 0x1009
        const val READ_LOCAL_VERSION = 0x1001
        const val READ_BUFFER_SIZE = 0x1005
        const val CREATE_CONNECTION = 0x0405
        const val AUTHENTICATION_REQUESTED = 0x0411
        const val SET_CONNECTION_ENCRYPTION = 0x0413

        const val SERVICE_SDP = "sdp"
        const val SERVICE_RFCOMM = "rfcomm"
    }
}
