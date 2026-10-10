package com.shilapi.xcertplay.transport.hci

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class PairingState { IDLE, BROADCASTING, PAIRING, PAIRED, STALLED, FAILED }

/**
 * Answers the adapter's pairing traffic and remembers link keys.
 *
 * All the cryptography is the adapter's; this only replies to the events the controller hands it. It is
 * invoked on the controller's reader thread, so every reply is fire-and-forget ([HciController.send]) — a
 * reply that waited for its own completion would wait on the thread that is running it.
 *
 * The connection, connection-request and encryption events are acted on but not consumed: the layer above also
 * reads them.
 */
internal class SspPairingAgent(
    private val controller: HciController,
    private val store: SspLinkStore,
    private val onState: (PairingState, String) -> Unit = { _, _ -> },
    private val onDiagnostic: (String) -> Unit = {},
    private val pairingStallMillis: Long = PAIRING_STALL_MILLIS,
) : (HciEventPacket) -> Boolean {
    /** The adapter's own address, from Read_BD_ADDR. */
    @Volatile var localAddress: String? = null
        private set

    /** The peer we have paired with, or are connected to. */
    @Volatile var pairedAddress: String? = null
        private set

    /** The live ACL handle, or -1. */
    @Volatile var aclHandle: Int = -1
        private set

    @Volatile var localVersion: LocalVersion? = null
        private set

    @Volatile var linkEncrypted = false
        private set

    /** The controller's answer to Authentication_Requested: pending, then its HCI status. */
    @Volatile private var authenticationStatus = AUTHENTICATION_PENDING

    @Volatile var state: PairingState = PairingState.IDLE
        private set

    private val pairingStartedAt = AtomicLong(0L)
    private val stallWatchdogStarted = AtomicBoolean(false)

    /**
     * Reset the adapter, then name it.
     *
     * [discoverable] false still brings the radio up, but inquiry and page scan stay off until
     * something asks for them. The car hotspot has to be on before the name is worth showing.
     */
    fun advertise(broadcastName: String, discoverable: Boolean = true): Boolean {
        if (!accepted(HciCommands.reset(), "hci-reset")) return false
        // Reset clears controller state, so everything below has to follow it.
        Thread.sleep(RESET_SETTLE_MILLIS)

        val address = readBdAddr()
        if (address == null) {
            onDiagnostic("adapter-bt: read-bd-addr failed")
            transition(PairingState.FAILED, "read-bd-addr")
            return false
        }
        localAddress = address
        localVersion = readLocalVersion()
        readBufferSize()
        onDiagnostic(
            "adapter-bt: adapter=$address hci=${localVersion?.hciVersion} " +
                "lmp=${localVersion?.lmpVersion} vendor=0x${localVersion?.manufacturer?.toString(16)}",
        )

        if (!accepted(HciCommands.writeLocalName(broadcastName), "write-local-name")) return false
        // Asked here because the answer is one of the EIR's own structures, and asked in a way that cannot
        // fail the bring-up: see [readInquiryResponseTransmitPower].
        val transmitPower = readInquiryResponseTransmitPower()
        val eir = HciCommands.writeExtendedInquiryResponse(
            broadcastName,
            transmitPower ?: TX_POWER_NOT_REPORTED,
        )
        if (!accepted(eir, "write-eir")) return false
        if (!accepted(HciCommands.writeClassOfDevice(ActionsBluetooth.CLASS_OF_DEVICE), "write-cod")) return false
        val scan = if (discoverable) ActionsBluetooth.SCAN_ENABLE_DISCOVERABLE_CONNECTABLE else 0
        if (!accepted(HciCommands.writeScanEnable(scan), "write-scan-enable")) return false
        if (!accepted(HciCommands.writeSimplePairingMode(true), "write-ssp-mode")) return false

        if (discoverable) {
            transition(PairingState.BROADCASTING, "advertising")
        } else {
            transition(PairingState.IDLE, "hotspot-off")
        }
        return true
    }

    /**
     * Authenticate the live ACL and then turn encryption on, each step waiting for the controller to confirm it.
     *
     * The order is the spec's and it is not interchangeable: encryption is only meaningful on an authenticated
     * link, and asking for it while authentication is still in flight gets a status that cannot be told apart
     * from a rejected key. Authentication is read from event 0x06 and encryption from 0x08.
     */
    fun secureLink(): Boolean {
        val handle = aclHandle
        if (handle < 0) {
            onDiagnostic("adapter-bt: no ACL connection to secure")
            return false
        }

        authenticationStatus = AUTHENTICATION_PENDING
        if (!accepted(HciCommands.authenticationRequested(handle), "authentication-requested")) return false
        if (!awaitAuthentication()) return false

        linkEncrypted = false
        if (!accepted(HciCommands.setConnectionEncryption(handle, true), "set-connection-encryption")) return false

        val deadline = System.currentTimeMillis() + SECURE_LINK_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (linkEncrypted) return true
            Thread.sleep(SECURE_POLL_MILLIS)
        }
        onDiagnostic("adapter-bt: link was not encrypted within ${SECURE_LINK_TIMEOUT_MILLIS}ms")
        return false
    }

    /**
     * Waits for the controller to report how authentication went.
     *
     * The status ends the wait, not a flag: a link key the phone has forgotten comes back non-zero at once, and
     * sitting out the whole timeout would report a forgotten pairing as silence.
     */
    private fun awaitAuthentication(): Boolean {
        val deadline = System.currentTimeMillis() + SECURE_LINK_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (authenticationStatus != AUTHENTICATION_PENDING) return authenticationStatus == 0
            Thread.sleep(SECURE_POLL_MILLIS)
        }
        onDiagnostic("adapter-bt: authentication did not complete within ${SECURE_LINK_TIMEOUT_MILLIS}ms")
        return false
    }

    override fun invoke(event: HciEventPacket): Boolean = when (event.code) {
        HciEvents.IO_CAPABILITY_REQUEST -> {
            val address = HciEvents.ioCapabilityRequest(event)
            beginPairing()
            reply(
                HciCommands.ioCapabilityRequestReply(
                    address,
                    HciCommands.IO_CAPABILITY_NO_INPUT_NO_OUTPUT,
                    OOB_DATA_NOT_PRESENT,
                    HciCommands.AUTHENTICATION_REQUIREMENTS_DEDICATED_BONDING,
                ),
                "io-capability-request-reply",
            )
            transition(PairingState.PAIRING, "io-capability-request")
            true
        }

        HciEvents.USER_CONFIRMATION_REQUEST -> {
            val address = HciEvents.userConfirmationRequest(event)
            beginPairing()
            reply(HciCommands.userConfirmationRequestReply(address), "user-confirmation-request-reply")
            transition(PairingState.PAIRING, "user-confirmation-request")
            true
        }

        HciEvents.LINK_KEY_REQUEST -> {
            val address = HciEvents.linkKeyRequest(event)
            val stored = store.key(address)
            if (stored == null) {
                onDiagnostic("adapter-bt: no stored link key for $address")
                reply(HciCommands.linkKeyRequestNegativeReply(address), "link-key-request-negative-reply")
            } else {
                reply(HciCommands.linkKeyRequestReply(address, stored.linkKey), "link-key-request-reply")
                pairedAddress = address
            }
            true
        }

        HciEvents.LINK_KEY_NOTIFICATION -> {
            val notification = HciEvents.linkKeyNotification(event)
            store.put(notification.address, notification.linkKey, notification.keyType)
            pairedAddress = notification.address
            onDiagnostic(
                "adapter-bt: stored link key for ${notification.address} type=${notification.keyType}",
            )
            transition(PairingState.PAIRED, "link-key-notification")
            true
        }

        HciEvents.PIN_CODE_REQUEST -> {
            // iOS pairs with SSP; a PIN request means something is wrong, and a negative reply restarts it.
            val address = HciEvents.pinCodeRequest(event)
            onDiagnostic("adapter-bt: unexpected PIN code request from $address")
            reply(HciCommands.pinCodeRequestNegativeReply(address), "pin-code-request-negative-reply")
            true
        }

        HciEvents.SIMPLE_PAIRING_COMPLETE -> {
            val (status, address) = HciEvents.simplePairingComplete(event)
            if (status != 0) {
                onDiagnostic("adapter-bt: pairing failed status=0x${status.toString(16)} with $address")
                forgetKeyIfMissing(status, address)
                transition(PairingState.FAILED, "simple-pairing-complete")
            }
            true
        }

        // The iPhone pages us when someone taps the name in Settings, so the link is ours to accept. An
        // unanswered request is dropped by the controller once Connection_Accept_Timeout runs out, and nothing
        // else reports it: the phone shows Connecting and the stack stays on "broadcasting".
        HciEvents.CONNECTION_REQUEST -> {
            val address = HciEvents.connectionRequest(event)
            onDiagnostic("adapter-bt: incoming connection from $address accepted")
            reply(
                HciCommands.acceptConnectionRequest(address, HciCommands.ROLE_REMAIN_SLAVE),
                "accept-connection-request",
            )
            false
        }

        HciEvents.CONNECTION_COMPLETE -> {
            val complete = HciEvents.connectionComplete(event)
            if (complete.status == 0) {
                aclHandle = complete.handle
                pairedAddress = complete.address
                onDiagnostic("adapter-bt: acl handle=0x${complete.handle.toString(16)} peer=${complete.address}")
            } else {
                onDiagnostic(
                    "adapter-bt: connection failed status=0x${complete.status.toString(16)} peer=${complete.address}",
                )
            }
            false
        }

        HciEvents.DISCONNECTION_COMPLETE -> {
            val complete = HciEvents.disconnectionComplete(event)
            if (complete.handle == aclHandle) aclHandle = -1
            onDiagnostic(
                "adapter-bt: disconnected handle=0x${complete.handle.toString(16)} " +
                    "reason=0x${complete.reason.toString(16)}",
            )
            false
        }

        HciEvents.AUTHENTICATION_COMPLETE -> {
            val complete = HciEvents.authenticationComplete(event)
            authenticationStatus = complete.status
            if (complete.status != 0) {
                onDiagnostic(
                    "adapter-bt: authentication failed status=0x${complete.status.toString(16)} " +
                        "handle=0x${complete.handle.toString(16)}",
                )
                forgetKeyIfMissing(complete.status, pairedAddress)
            }
            false
        }

        HciEvents.ENCRYPTION_CHANGE -> {
            val change = HciEvents.encryptionChange(event)
            linkEncrypted = change.status == 0 && change.enabled
            onDiagnostic(
                "adapter-bt: encryption change handle=0x${change.handle.toString(16)} " +
                    "enabled=${change.enabled} status=0x${change.status.toString(16)}",
            )
            false
        }

        // An event this stack does not read is still evidence, and dropping it in silence is what made a missing
        // step look like a step that never happened: with only "advertising" in the log, "not implemented" and
        // "the adapter sent nothing" are the same line. The number goes in so the next run can be read against
        // the spec, and the parameters so an event nobody here recognises can still be identified.
        else -> {
            onDiagnostic("adapter-bt: unhandled event 0x${hex(event.code)} params=${hex(event.parameters)}")
            false
        }
    }

    /**
     * Drops the stored key when the failure is the phone saying it has none.
     *
     * A stale key fails every later attempt the same way, and nothing else in this stack ever removes one, so a
     * head unit that reached this state could only be rescued by clearing app data. 0x06 is the one status that
     * says the other side's key is gone rather than that the attempt was unlucky, so it is the only one that
     * leaves a stored key worth dropping.
     */
    private fun forgetKeyIfMissing(status: Int, address: String?) {
        if (status != HciEvents.PIN_OR_KEY_MISSING || address == null) return
        onDiagnostic("adapter-bt: the phone has no link key for $address; forgetting ours")
        store.forget(address)
    }

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0')

    private fun hex(bytes: ByteArray): String =
        if (bytes.isEmpty()) "none" else bytes.joinToString("") { hex(it.toInt() and 0xFF) }

    private fun readBdAddr(): String? = when (val result = controller.command(HciCommands.readBdAddr())) {
        is HciCommandResult.Complete -> HciEvents.readBdAddrReturn(result.returnParameters)
        else -> null
    }

    private fun readLocalVersion(): LocalVersion? =
        when (val result = controller.command(HciCommands.readLocalVersion())) {
            is HciCommandResult.Complete -> HciEvents.readLocalVersionReturn(result.returnParameters)
            else -> null
        }

    private fun readBufferSize() {
        val result = controller.command(HciCommands.readBufferSize())
        if (result !is HciCommandResult.Complete) return
        val buffer = HciEvents.readBufferSizeReturn(result.returnParameters)
        controller.applyBufferSize(buffer.aclMtu, buffer.aclMaxPackets)
    }

    /**
     * The adapter's own reported inquiry-response transmit power in dBm, or null when it will not say.
     *
     * Unlike every other read in [advertise], a refusal here does not end the bring-up. The name is already
     * written and the phone can already see it; losing the whole broadcast over one optional structure would
     * trade a spec detail for the only thing that makes the rest testable. The null is said out loud so a
     * report can tell a radio that would not answer from one that answered zero.
     */
    private fun readInquiryResponseTransmitPower(): Int? {
        val result = controller.command(HciCommands.readInquiryResponseTransmitPower())
        val power = if (result is HciCommandResult.Complete) {
            HciEvents.readInquiryResponseTransmitPowerReturn(result.returnParameters)
        } else {
            null
        }
        if (power == null) onDiagnostic("adapter-bt: inquiry-tx-power unavailable")
        return power
    }

    private fun accepted(packet: ByteArray, label: String): Boolean {
        val result = controller.command(packet)
        val accepted = result is HciCommandResult.Complete || result == HciCommandResult.Status(0)
        if (!accepted) {
            val detail = when (result) {
                is HciCommandResult.Failed -> "status=0x${result.status.toString(16)}"
                HciCommandResult.TimedOut -> "timed out"
                else -> "unexpected result"
            }
            onDiagnostic("adapter-bt: $label rejected ($detail)")
            transition(PairingState.FAILED, label)
        }
        return accepted
    }

    private fun reply(packet: ByteArray, label: String) {
        try {
            controller.send(packet)
        } catch (error: IOException) {
            onDiagnostic("adapter-bt: $label not sent: ${error.javaClass.simpleName}")
        }
    }

    private fun beginPairing() {
        val previous = state
        pairingStartedAt.set(System.currentTimeMillis())
        // A finished attempt leaves its watchdog spent, so a pairing that is tried again after a stall, a
        // failure or a success gets one of its own; otherwise a second silent attempt would never be reported.
        if (
            previous == PairingState.STALLED ||
            previous == PairingState.FAILED ||
            previous == PairingState.PAIRED
        ) {
            stallWatchdogStarted.set(false)
        }
        startStallWatchdog()
    }

    /**
     * A pairing that starts and then goes quiet is the one failure the adapter cannot report: it looks the same
     * as a user who never tapped the phone. A pairing that never started is not stalled, so the watchdog only
     * runs once a pairing event has arrived.
     */
    private fun startStallWatchdog() {
        if (!stallWatchdogStarted.compareAndSet(false, true)) return
        Thread(::watchForStall, "adapter-bt-pairing-watchdog").apply { isDaemon = true }.start()
    }

    private fun watchForStall() {
        while (true) {
            Thread.sleep(STALL_POLL_MILLIS)
            when (state) {
                PairingState.IDLE, PairingState.BROADCASTING, PairingState.PAIRING -> Unit
                else -> return
            }
            if (state == PairingState.PAIRING &&
                System.currentTimeMillis() - pairingStartedAt.get() >= pairingStallMillis
            ) {
                onDiagnostic("adapter-bt: pairing stalled with no link key after ${pairingStallMillis}ms")
                transition(PairingState.STALLED, "pairing-stalled")
                return
            }
        }
    }

    private fun transition(next: PairingState, reason: String) {
        state = next
        onState(next, reason)
    }

    private companion object {
        const val RESET_SETTLE_MILLIS = 200L
        const val OOB_DATA_NOT_PRESENT = 0

        /** 0 dBm: what the EIR carries when the adapter will not report a power of its own. */
        const val TX_POWER_NOT_REPORTED = 0
        const val AUTHENTICATION_PENDING = -1
        const val PAIRING_STALL_MILLIS = 30_000L
        const val STALL_POLL_MILLIS = 250L
        const val SECURE_LINK_TIMEOUT_MILLIS = 10_000L
        const val SECURE_POLL_MILLIS = 25L
    }
}
