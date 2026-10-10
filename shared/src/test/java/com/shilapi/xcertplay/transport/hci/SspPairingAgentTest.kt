package com.shilapi.xcertplay.transport.hci

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SspPairingAgentTest {
    @get:Rule val folder = TemporaryFolder()

    private val phone = "CC:60:23:D7:2F:5B"
    private val adapter = "F4:4E:FC:F8:8B:36"

    private fun store() = SspLinkStore(File(folder.root, "keys.tsv"))

    private fun controller(transport: FakeHciTransport) =
        HciController(transport, {}, {}).also { it.start() }

    // The event codes below are literal on purpose: they are what the adapter puts on the wire, and a test that
    // fed the stack's own constants would agree with whatever those constants say — which is how the Secure
    // Simple Pairing events came to be numbered after the reply commands' OCFs and still pass every test.
    private fun ioCapabilityRequestFrom(address: String) =
        HciEventPacket(0x31, HciCommands.formatAddress(address))

    private fun connectionRequestFrom(address: String) = HciEventPacket(
        0x04,
        // BD_ADDR, Class_Of_Device, Link_Type: an ACL link being asked for, which only the host may accept.
        HciCommands.formatAddress(address) + byteArrayOf(0x04, 0x04, 0x24, 0x01),
    )

    private fun userConfirmationRequestFrom(address: String) = HciEventPacket(
        0x33,
        // BD_ADDR and the numeric value; Just Works always carries zero.
        HciCommands.formatAddress(address) + byteArrayOf(0x00, 0x00, 0x00, 0x00),
    )

    private fun simplePairingComplete(status: Int, address: String) = HciEventPacket(
        0x36,
        byteArrayOf(status.toByte()) + HciCommands.formatAddress(address),
    )

    private fun opcodeOf(packet: ByteArray): Int =
        (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)

    private fun connected(handle: Int) = HciEventPacket(
        0x03,
        byteArrayOf(0x00, (handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte()) +
            HciCommands.formatAddress(phone) +
            byteArrayOf(0x01, 0x00),
    )

    private fun encryptionChange(handle: Int, enabled: Boolean) = HciEventPacket(
        0x08,
        byteArrayOf(0x00, (handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte(), if (enabled) 1 else 0),
    )

    private fun authenticationComplete(handle: Int, status: Int = 0) = HciEventPacket(
        0x06,
        byteArrayOf(status.toByte(), (handle and 0xFF).toByte(), ((handle shr 8) and 0xFF).toByte()),
    )

    private fun awaitCommand(transport: FakeHciTransport, opcode: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (transport.commands.any { opcodeOf(it) == opcode }) return
            Thread.sleep(10)
        }
        assertTrue("expected command 0x${opcode.toString(16)}", transport.commands.any { opcodeOf(it) == opcode })
    }

    /** A pairing that is tried again after a stall must be watched again, or the retry can hang silently. */
    @Test fun aSecondPairingAttemptGetsItsOwnStallWatchdog() {
        val transport = FakeHciTransport()
        val states = CopyOnWriteArrayList<PairingState>()
        val controller = controller(transport)
        val agent = SspPairingAgent(
            controller,
            store(),
            { state, _ -> states += state },
            pairingStallMillis = 60,
        )

        agent(ioCapabilityRequestFrom(phone))
        awaitState(states, PairingState.STALLED)

        states.clear()
        agent(ioCapabilityRequestFrom(phone))
        awaitState(states, PairingState.STALLED)

        controller.close()
    }

    private fun awaitState(states: List<PairingState>, expected: PairingState) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (states.contains(expected)) return
            Thread.sleep(10)
        }
        assertTrue("expected $expected, saw $states", states.contains(expected))
    }

    @Test fun ioCapabilityRequestIsAnsweredWithNoInputNoOutput() {
        val transport = FakeHciTransport()
        val states = CopyOnWriteArrayList<Pair<PairingState, String>>()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store(), { state, reason -> states += state to reason })

        agent(ioCapabilityRequestFrom(phone))

        val sent = transport.commands.last()
        assertEquals(0x042B, (sent[0].toInt() and 0xFF) or ((sent[1].toInt() and 0xFF) shl 8))
        assertEquals(HciCommands.IO_CAPABILITY_NO_INPUT_NO_OUTPUT, sent[9].toInt() and 0xFF)
        assertTrue(states.contains(PairingState.PAIRING to "io-capability-request"))
        controller.close()
    }

    /**
     * The phone pages the adapter, and only the host may answer: an unanswered Connection_Request is rejected by
     * the controller, and the iPhone's "Connecting" never finishes.
     */
    @Test fun anIncomingConnectionIsAccepted() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store())

        assertEquals(false, agent(connectionRequestFrom(phone)))

        val sent = transport.commands.last()
        assertEquals(0x0409, opcodeOf(sent))
        assertEquals(7, sent[2].toInt() and 0xFF)
        assertEquals(phone, HciCommands.parseAddress(sent.copyOfRange(3, 9)))
        assertEquals(HciCommands.ROLE_REMAIN_SLAVE, sent[9].toInt() and 0xFF)
        controller.close()
    }

    /** Just Works still raises User_Confirmation_Request; leaving it unanswered is a phone that never pairs. */
    @Test fun aUserConfirmationRequestIsConfirmed() {
        val transport = FakeHciTransport()
        val states = CopyOnWriteArrayList<Pair<PairingState, String>>()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store(), { state, reason -> states += state to reason })

        assertEquals(true, agent(userConfirmationRequestFrom(phone)))

        val sent = transport.commands.last()
        assertEquals(0x042C, opcodeOf(sent))
        assertEquals(6, sent[2].toInt() and 0xFF)
        assertTrue(states.contains(PairingState.PAIRING to "user-confirmation-request"))
        controller.close()
    }

    @Test fun aFailedSimplePairingIsReported() {
        val transport = FakeHciTransport()
        val states = CopyOnWriteArrayList<Pair<PairingState, String>>()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store(), { state, reason -> states += state to reason })

        agent(simplePairingComplete(0x05, phone))

        assertTrue(states.contains(PairingState.FAILED to "simple-pairing-complete"))
        controller.close()
    }

    @Test fun linkKeyRequestIsAnsweredFromTheStoreForThatAddressOnly() {
        val transport = FakeHciTransport()
        val store = store()
        val key = ByteArray(16) { 0x5A }
        store.put(phone, key, 5)
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store)

        agent(HciEventPacket(0x17, HciCommands.formatAddress(phone)))

        val sent = transport.commands.last()
        assertEquals(0x040B, (sent[0].toInt() and 0xFF) or ((sent[1].toInt() and 0xFF) shl 8))
        assertEquals(16 + 6, sent[2].toInt() and 0xFF)
        assertEquals(phone, agent.pairedAddress)
        controller.close()
    }

    @Test fun anUnknownAddressGetsANegativeReplyNotAnotherDevicesKey() {
        val transport = FakeHciTransport()
        val store = store()
        store.put(adapter, ByteArray(16) { 0x11 }, 5)
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store)

        agent(HciEventPacket(0x17, HciCommands.formatAddress(phone)))

        val sent = transport.commands.last()
        assertEquals(0x040C, (sent[0].toInt() and 0xFF) or ((sent[1].toInt() and 0xFF) shl 8))
        assertEquals(6, sent[2].toInt() and 0xFF)
        controller.close()
    }

    @Test fun linkKeyNotificationIsPersistedAndThePeerBecomesTheTarget() {
        val transport = FakeHciTransport()
        val store = store()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store)
        val key = ByteArray(16) { it.toByte() }

        agent(HciEventPacket(0x18, HciCommands.formatAddress(phone) + key + byteArrayOf(0x05)))

        assertEquals(phone, agent.pairedAddress)
        assertEquals(key.toList(), store.key(phone)!!.linkKey.toList())
        controller.close()
    }

    @Test fun eventsThisAgentDoesNotOwnAreLeftToTheCaller() {
        val controller = controller(FakeHciTransport())
        val agent = SspPairingAgent(controller, store())
        assertEquals(false, agent(HciEventPacket(0x13, byteArrayOf(0x00))))
        controller.close()
    }

    /**
     * A gate of the link design: an event this stack does not read is still evidence. Dropped in silence it hides
     * the step that is missing — with only "advertising" in the log, "not implemented" and "the adapter sent
     * nothing" read the same, which is how the pairing gaps stayed invisible.
     */
    @Test fun anEventThisStackDoesNotReadIsReportedWithItsNumber() {
        val transport = FakeHciTransport()
        val diagnostics = CopyOnWriteArrayList<String>()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store(), onDiagnostic = diagnostics::add)

        // IO_Capability_Response: the phone declaring its own capabilities. Nothing here reads it and it needs
        // no reply, so it is exactly the kind of event that used to vanish without a trace.
        val payload = HciCommands.formatAddress(phone) + byteArrayOf(0x04, 0x00, 0x02)

        assertEquals(false, agent(HciEventPacket(0x32, payload)))

        assertTrue(diagnostics.any { it.contains("unhandled event 0x32 params=5b2fd72360cc040002") })
        controller.close()
    }

    /** A connection and its encryption are not the agent's to consume: the layer above also reads them. */
    @Test fun connectionAndEncryptionEventsAreObservedButNotConsumed() {
        val controller = controller(FakeHciTransport())
        val agent = SspPairingAgent(controller, store())

        assertEquals(false, agent(connected(0x0B)))
        assertEquals(0x0B, agent.aclHandle)
        assertEquals(false, agent(encryptionChange(0x0B, enabled = true)))
        assertTrue(agent.linkEncrypted)
        controller.close()
    }

    @Test fun advertiseResetsNamesScansAndReadsTheAdapter() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val states = CopyOnWriteArrayList<PairingState>()
        val agent = SspPairingAgent(controller, store(), { state, _ -> states += state })

        transport.enqueueOnCommand(commandComplete(0x0C03, 0))
        transport.enqueueOnCommand(commandComplete(0x1009, 0, HciCommands.formatAddress(adapter)))
        // The helper supplies the status byte; these are the returns that follow it.
        transport.enqueueOnCommand(commandComplete(0x1001, 0, byteArrayOf(0x06, 0x00, 0x00, 0x0A, 0x0F, 0x00)))
        transport.enqueueOnCommand(
            commandComplete(0x1005, 0, byteArrayOf(0xFB.toByte(), 0x00, 0x1E, 0x08, 0x00, 0x00, 0x00)),
        )
        transport.enqueueOnCommand(commandComplete(0x0C13, 0))
        transport.enqueueOnCommand(commandComplete(0x0C58, 0, byteArrayOf(0x14)))
        transport.enqueueOnCommand(commandComplete(0x0C52, 0))
        transport.enqueueOnCommand(commandComplete(0x0C24, 0))
        transport.enqueueOnCommand(commandComplete(0x0C1A, 0))
        transport.enqueueOnCommand(commandComplete(0x0C56, 0))

        assertTrue(agent.advertise(ActionsBluetooth.BROADCAST_NAME))

        assertEquals(adapter, agent.localAddress)
        assertEquals(10, agent.localVersion!!.lmpVersion)
        // The ACL payload an L2CAP PDU can carry is the ACL MTU less the L2CAP header.
        assertEquals(0x00FB - 4, controller.aclPayloadBytes)
        assertEquals(listOf(PairingState.BROADCASTING), states)
        assertEquals(ActionsBluetooth.BROADCAST_NAME, writtenLocalName(transport))
        // The adapter's own power reading, in the EIR between the UUID list and the name.
        assertArrayEquals(
            byteArrayOf(0x02, 0x0A, 0x14),
            extendedInquiryResponse(transport).copyOfRange(34, 37),
        )
        assertEquals(
            ActionsBluetooth.SCAN_ENABLE_DISCOVERABLE_CONNECTABLE,
            parameterOf(transport, opcode = 0x0C1A).toInt() and 0xFF,
        )
        controller.close()
    }

    @Test fun advertiseCanBringTheRadioUpWithoutMakingItVisible() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val states = CopyOnWriteArrayList<PairingState>()
        val agent = SspPairingAgent(controller, store(), { state, _ -> states += state })
        transport.enqueueOnCommand(commandComplete(0x0C03, 0))
        transport.enqueueOnCommand(commandComplete(0x1009, 0, HciCommands.formatAddress(adapter)))
        transport.enqueueOnCommand(commandComplete(0x1001, 0, byteArrayOf(0x06, 0x00, 0x00, 0x0A, 0x0F, 0x00)))
        transport.enqueueOnCommand(
            commandComplete(0x1005, 0, byteArrayOf(0xFB.toByte(), 0x00, 0x1E, 0x08, 0x00, 0x00, 0x00)),
        )
        transport.enqueueOnCommand(commandComplete(0x0C13, 0))
        transport.enqueueOnCommand(commandComplete(0x0C58, 0, byteArrayOf(0x14)))
        transport.enqueueOnCommand(commandComplete(0x0C52, 0))
        transport.enqueueOnCommand(commandComplete(0x0C24, 0))
        transport.enqueueOnCommand(commandComplete(0x0C1A, 0))
        transport.enqueueOnCommand(commandComplete(0x0C56, 0))

        assertTrue(agent.advertise(ActionsBluetooth.BROADCAST_NAME, discoverable = false))

        assertEquals(listOf(PairingState.IDLE), states)
        assertEquals(0, parameterOf(transport, opcode = 0x0C1A).toInt() and 0xFF)
        controller.close()
    }

    /**
     * A radio that refuses Read_Inquiry_Response_Transmit_Power_Level still advertises.
     *
     * Apple's Bluetooth Accessories spec wants the TX Power Level in the EIR, but by this point the name is
     * already written and the phone can already see it. Losing the broadcast over one optional structure would
     * cost the only thing that makes the rest of the chain testable, so the structure goes out with a zero and
     * the log says which of the two happened.
     */
    @Test fun aRadioThatWillNotReportItsPowerStillAdvertises() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val states = CopyOnWriteArrayList<PairingState>()
        val diagnostics = CopyOnWriteArrayList<String>()
        val agent = SspPairingAgent(
            controller,
            store(),
            { state, _ -> states += state },
            onDiagnostic = diagnostics::add,
        )
        transport.enqueueOnCommand(commandComplete(0x0C03, 0))
        transport.enqueueOnCommand(commandComplete(0x1009, 0, HciCommands.formatAddress(adapter)))
        transport.enqueueOnCommand(commandComplete(0x1001, 0, byteArrayOf(0x06, 0x00, 0x00, 0x0A, 0x0F, 0x00)))
        transport.enqueueOnCommand(
            commandComplete(0x1005, 0, byteArrayOf(0xFB.toByte(), 0x00, 0x1E, 0x08, 0x00, 0x00, 0x00)),
        )
        transport.enqueueOnCommand(commandComplete(0x0C13, 0))
        // Refused: a non-zero status, so there is no power to put in the EIR.
        transport.enqueueOnCommand(commandComplete(0x0C58, 0x01, byteArrayOf(0x14)))
        transport.enqueueOnCommand(commandComplete(0x0C52, 0))
        transport.enqueueOnCommand(commandComplete(0x0C24, 0))
        transport.enqueueOnCommand(commandComplete(0x0C1A, 0))
        transport.enqueueOnCommand(commandComplete(0x0C56, 0))

        assertTrue(agent.advertise(ActionsBluetooth.BROADCAST_NAME))

        assertEquals(listOf(PairingState.BROADCASTING), states)
        assertArrayEquals(
            byteArrayOf(0x02, 0x0A, 0x00),
            extendedInquiryResponse(transport).copyOfRange(34, 37),
        )
        assertTrue(diagnostics.any { it.contains("inquiry-tx-power unavailable") })
        controller.close()
    }

    @Test fun advertiseStopsAtTheFirstCommandTheAdapterRefuses() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val states = CopyOnWriteArrayList<PairingState>()
        val agent = SspPairingAgent(controller, store(), { state, _ -> states += state })

        // Read_BD_ADDR is answered with a non-zero status.
        transport.enqueueOnCommand(commandComplete(0x0C03, 0))
        transport.enqueueOnCommand(commandComplete(0x1009, 0x01))

        assertFalse(agent.advertise(ActionsBluetooth.BROADCAST_NAME))

        assertEquals(null, agent.localAddress)
        assertTrue(states.contains(PairingState.FAILED))
        // Nothing was written: the name command never went out.
        assertTrue(transport.commands.none { (it[0].toInt() and 0xFF) == 0x13 })
        controller.close()
    }

    @Test fun secureLinkSucceedsOnlyOnceTheLinkIsEncrypted() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store())
        agent(connected(0x0B))
        transport.enqueueOnCommand(commandStatus(0x0411, 0))
        transport.enqueueOnCommand(commandStatus(0x0413, 0))
        val phoneAnswers = Thread {
            Thread.sleep(100)
            agent(authenticationComplete(0x0B))
        }
        phoneAnswers.start()
        val phoneSendsEncryptionChange = Thread {
            Thread.sleep(200)
            agent(encryptionChange(0x0B, enabled = true))
        }
        phoneSendsEncryptionChange.start()

        assertTrue(agent.secureLink())

        phoneAnswers.join()
        phoneSendsEncryptionChange.join()
        controller.close()
    }

    /**
     * Encryption is only meaningful on an authenticated link, and asking for it early gets a status that cannot
     * be told apart from a rejected key. It must not go out until the controller has answered Authentication_
     * Requested with event 0x06.
     */
    @Test fun encryptionIsNotAskedForBeforeAuthenticationCompletes() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store())
        agent(connected(0x0B))
        transport.enqueueOnCommand(commandStatus(0x0411, 0))
        transport.enqueueOnCommand(commandStatus(0x0413, 0))

        Thread { agent.secureLink() }.apply { isDaemon = true }.start()

        awaitCommand(transport, 0x0411)
        // Authentication is in flight and unanswered, so encryption has no business being asked for yet.
        assertFalse(transport.commands.any { opcodeOf(it) == 0x0413 })

        agent(authenticationComplete(0x0B))

        awaitCommand(transport, 0x0413)
        controller.close()
    }

    /** A link key the phone has forgotten has to fail at once, not sit out the timeout looking like silence. */
    @Test fun aFailedAuthenticationEndsTheWaitImmediately() {
        val transport = FakeHciTransport()
        val diagnostics = CopyOnWriteArrayList<String>()
        val controller = controller(transport)
        val agent = SspPairingAgent(controller, store(), onDiagnostic = diagnostics::add)
        agent(connected(0x0B))
        transport.enqueueOnCommand(commandStatus(0x0411, 0))

        Thread {
            Thread.sleep(100)
            // Authentication_Complete with status 0x05: authentication failure.
            agent(authenticationComplete(0x0B, status = 0x05))
        }.apply { isDaemon = true }.start()

        val startedAt = System.currentTimeMillis()
        assertFalse(agent.secureLink())
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("should fail on the status, not the timeout (took ${elapsed}ms)", elapsed < 5_000)
        assertTrue(diagnostics.any { it.contains("authentication failed status=0x5") })
        assertFalse(transport.commands.any { opcodeOf(it) == 0x0413 })
        controller.close()
    }

    @Test fun secureLinkFailsWhenThereIsNoLinkToSecure() {
        val controller = controller(FakeHciTransport())
        val agent = SspPairingAgent(controller, store())
        assertFalse(agent.secureLink())
        controller.close()
    }

    @Test fun aPairingThatGoesQuietAfterItStartedIsReportedStalled() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val states = CopyOnWriteArrayList<PairingState>()
        val agent = SspPairingAgent(controller, store(), { state, _ -> states += state }, pairingStallMillis = 200)

        agent(ioCapabilityRequestFrom(phone))
        Thread.sleep(600)

        assertTrue(states.contains(PairingState.STALLED))
        controller.close()
    }

    /** Nothing was ever negotiated, so there is nothing to time out. */
    @Test fun anAdapterThatNeverSawAPairingEventIsNotStalled() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val states = CopyOnWriteArrayList<PairingState>()
        val agent = SspPairingAgent(controller, store(), { state, _ -> states += state }, pairingStallMillis = 150)

        Thread.sleep(500)

        assertTrue(states.isEmpty())
        controller.close()
    }

    /**
     * A phone that no longer holds the link key reports 0x06, and our half of it has to go with theirs.
     *
     * A link key is a shared secret, so one side cannot repair it alone: keeping ours makes every later attempt
     * fail identically, and nothing else in this stack removes a key, so the head unit would only be recoverable
     * by clearing app data.
     */
    @Test fun aPhoneThatHasLostTheKeyMakesUsForgetOurs() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val store = store()
        store.put(phone, ByteArray(16) { 0x11 }, 4)
        val agent = SspPairingAgent(controller, store)

        agent(connected(0x0B))
        agent(authenticationComplete(0x0B, status = 0x06))

        assertNull("a key the phone says it does not have must not be kept", store.key(phone))
        controller.close()
    }

    /** The same status arrives on the pairing's own event when the key is the thing that failed. */
    @Test fun aPairingThatEndsWithTheKeyMissingForgetsIt() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val store = store()
        store.put(phone, ByteArray(16) { 0x11 }, 4)
        val agent = SspPairingAgent(controller, store)

        agent(simplePairingComplete(status = 0x06, address = phone))

        assertNull(store.key(phone))
        controller.close()
    }

    /** 0x06 is the one status that blames the key; any other failure leaves a usable key in place. */
    @Test fun aFailureThatIsNotTheMissingKeyKeepsOurs() {
        val transport = FakeHciTransport()
        val controller = controller(transport)
        val store = store()
        store.put(phone, ByteArray(16) { 0x11 }, 4)
        val agent = SspPairingAgent(controller, store)

        agent(simplePairingComplete(status = 0x05, address = phone))

        assertNotNull("only the missing-key status says the stored key is the problem", store.key(phone))
        controller.close()
    }

    private fun writtenLocalName(transport: FakeHciTransport): String {
        val packet = transport.commands.first { (it[0].toInt() and 0xFF) == 0x13 }
        return String(packet, 3, ActionsBluetooth.BROADCAST_NAME.length)
    }

    /** The 240 octets of EIR the stack wrote, past the FEC_Required octet in front of them. */
    private fun extendedInquiryResponse(transport: FakeHciTransport): ByteArray =
        transport.commands.first { (it[0].toInt() and 0xFF) == 0x52 }.copyOfRange(4, 244)

    private fun parameterOf(transport: FakeHciTransport, opcode: Int): Byte =
        transport.commands.first { (it[0].toInt() and 0xFF) == (opcode and 0xFF) }[3]
}
