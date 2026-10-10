package com.shilapi.xcertplay.transport.hci

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.PairedTarget
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Where the adapter hop has got to, for the connection page and the handshake log. */
enum class AdapterBluetoothState {
    IDLE,
    RESETTING,
    UNADVERTISED,
    BROADCASTING,
    PAIRING,
    PAIRING_STALLED,
    NOT_PAIRED,
    ACL_FAILED,
    SDP_NO_IAP2_SERVICE,
    RFCOMM_FAILED,
    READY,
    FAILED,
}

/**
 * The whole user-space Bluetooth host stack over one adapter, behind a single facade.
 *
 * Takes an [HciTransport] rather than a USB device so the entire chain — pairing, L2CAP, SDP, RFCOMM and the
 * byte stream — can be driven by a fake in tests. Whoever owns the USB interface (the connection page) owns
 * [close]; this class owns everything above it.
 */
class ActionsHciHost(
    private val transport: HciTransport,
    linkKeyFile: File,
    private val onState: (AdapterBluetoothState, String) -> Unit = { _, _ -> },
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val store = SspLinkStore(linkKeyFile)
    private val controller = HciController(transport, ::onAcl, ::onEvent, onDiagnostic)
    private val agent = SspPairingAgent(controller, store, ::onPairingState, onDiagnostic)
    private val engine = L2capEngine(
        object : AclSender {
            override val aclPayloadBytes: Int get() = controller.aclPayloadBytes

            override fun sendAclPayload(handle: Int, packetBoundary: Int, payload: ByteArray) =
                controller.sendAcl(handle, packetBoundary, payload)
        },
        ::onChannelData,
        onDiagnostic,
    )
    private val sdp = SdpClient(engine, onDiagnostic)
    private val rfcomm = RfcommChannel(engine, ::onRfcommData, onDiagnostic, ::onRfcommPeerEnded)

    private val closed = AtomicBoolean(false)
    private val discoveryLock = Any()
    private val discoveryHeld = AtomicBoolean(false)

    @Volatile private var stream: AdapterRfcommStream? = null
    @Volatile private var aclHandle = -1
    @Volatile private var scanning = false

    val localAddress: String? get() = agent.localAddress

    val hciVersion: Int? get() = agent.localVersion?.hciVersion

    /**
     * Resets the adapter and names it. [discoverable] false leaves inquiry and page scan off, so
     * the iPhone does not see the name until the car hotspot is known to be on.
     * False means it will never work.
     */
    fun start(discoverable: Boolean = true): Boolean {
        transition(AdapterBluetoothState.RESETTING, "start")
        controller.start()
        if (!agent.advertise(ActionsBluetooth.BROADCAST_NAME, discoverable)) {
            // advertise() already reported the command that was rejected. Replacing it with
            // "advertise" hid whether the failure was the name, the inquiry response, or scan enable.
            return false
        }
        synchronized(discoveryLock) { scanning = discoverable }
        if (discoverable) {
            transition(AdapterBluetoothState.BROADCASTING, "advertising")
        } else {
            transition(AdapterBluetoothState.UNADVERTISED, "hotspot-off")
        }
        return true
    }

    /**
     * Turns inquiry and page scan on or off.
     *
     * A session that has taken the radio quiet ignores this until [resumeDiscovery]. Repeating the
     * same request does not send another command: the connection page asks every couple of seconds.
     */
    fun setDiscoverable(enabled: Boolean): Boolean {
        val changed = synchronized(discoveryLock) {
            if (closed.get() || discoveryHeld.get()) return false
            if (scanning == enabled) return true
            if (!writeScan(if (enabled) ActionsBluetooth.SCAN_ENABLE_DISCOVERABLE_CONNECTABLE else 0)) {
                return false
            }
            if (discoveryHeld.get()) {
                writeScan(0)
                scanning = false
                return false
            }
            scanning = enabled
            true
        }
        if (changed) {
            if (enabled) transition(AdapterBluetoothState.BROADCASTING, "advertising")
            else transition(AdapterBluetoothState.UNADVERTISED, "hotspot-off")
        }
        return true
    }

    /**
     * After Wi-Fi has the session, drop the adapter's ACL and stop it being found.
     *
     * The car's own Bluetooth is not this radio. A failed attempt must not call this: scan would
     * stay off and the iPhone could not pair again. The hold is taken before the disconnect so a
     * refresh cannot turn scan back on in between.
     */
    fun idleAfterSession() {
        val handle = synchronized(discoveryLock) {
            if (!discoveryHeld.compareAndSet(false, true)) return
            scanning = false
            when {
                agent.aclHandle >= 0 -> agent.aclHandle
                aclHandle >= 0 -> aclHandle
                else -> -1
            }
        }
        if (handle >= 0) {
            runCatching {
                controller.command(
                    HciCommands.disconnect(handle, REMOTE_USER_TERMINATED_CONNECTION),
                    IDLE_COMMAND_TIMEOUT_MILLIS,
                )
            }
        }
        synchronized(discoveryLock) {
            // A new attempt may have resumed discovery while the disconnect was in flight.
            if (!discoveryHeld.get()) return
            writeScan(0)
            scanning = false
        }
        onDiagnostic("adapter-bt: session idle; adapter disconnected and not discoverable")
    }

    /** Lets the next refresh turn scan back on. Does not send a command by itself. */
    fun resumeDiscovery() {
        discoveryHeld.set(false)
    }

    /** The iPhone to open the iAP2 channel to, or null while nothing has paired yet. */
    fun pairedTarget(): PairedTarget? {
        // The most recently stored key, not "the only one": a head unit is often shared, and reading several stored
        // phones as "nothing paired" handed the run to the car's own Bluetooth — which cannot carry the iAP2 channel
        // at all — instead of to the adapter that can.
        val address = agent.pairedAddress ?: store.addresses().lastOrNull() ?: return null
        return PairedTarget(name = null, address = address)
    }

    /** Connects, secures, finds the iAP2 service and opens its RFCOMM channel. */
    fun openStream(timeoutMillis: Long = OPEN_TIMEOUT_MILLIS): BlockingDuplexByteStream {
        // A previous session may have held discovery off. A new attempt, including one that fails,
        // has to be allowed to advertise again or the iPhone cannot be paired a second time.
        resumeDiscovery()
        val target = pairedTarget() ?: run {
            transition(AdapterBluetoothState.NOT_PAIRED, "no paired iPhone")
            throw IOException("no paired iPhone yet; pair one and try again")
        }
        val handle = connect(target.address, timeoutMillis)
        if (handle < 0) {
            transition(AdapterBluetoothState.ACL_FAILED, "create-connection")
            throw IOException("could not open an ACL link to ${target.address}")
        }
        if (!agent.secureLink()) {
            transition(AdapterBluetoothState.ACL_FAILED, "authentication")
            throw IOException("the link to ${target.address} was not authenticated and encrypted")
        }

        val channel = try {
            sdp.findRfcommChannel(handle, timeoutMillis)
        } catch (error: IOException) {
            transition(AdapterBluetoothState.SDP_NO_IAP2_SERVICE, "sdp")
            throw error
        }
        // The channel SDP named is what the DLC is opened on. The CarPlay specification is explicit that the
        // accessory must query for it and "must not assume that the channel will remain the same".
        val dlci = try {
            rfcomm.open(handle, channel, timeoutMillis)
        } catch (error: IOException) {
            transition(AdapterBluetoothState.RFCOMM_FAILED, "rfcomm")
            throw error
        }

        val opened = AdapterRfcommStream(rfcomm, handle, dlci, onDiagnostic)
        stream = opened
        onDiagnostic("adapter-bt: iAP2 RFCOMM channel open (sdp channel=$channel dlci=$dlci)")
        transition(AdapterBluetoothState.READY, "iap2 stream open")
        return opened
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream?.close() }
        stream = null
        controller.close()
    }

    private fun connect(address: String, timeoutMillis: Long): Int {
        val existing = agent.aclHandle
        if (existing >= 0) return existing
        val result = controller.command(
            HciCommands.createConnection(address, ALLOW_ROLE_SWITCH),
            timeoutMillis,
        )
        if (result is HciCommandResult.Failed) return -1

        // Create_Connection is only acknowledged; the handle arrives with Connection_Complete.
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val handle = agent.aclHandle
            if (handle >= 0) return handle
            Thread.sleep(CONNECT_POLL_MILLIS)
        }
        return -1
    }

    private fun onAcl(data: HciAclData) {
        if (data.handle >= 0) aclHandle = data.handle
        engine.handleAcl(data)
    }

    private fun onEvent(event: HciEventPacket) {
        agent(event)
    }

    /**
     * Routes an inbound PDU to the half of the stack that owns its channel.
     *
     * By the PSM the channel was opened on, not by comparing channel ids. The phone dials the RFCOMM channel
     * our SDP answer publishes, and that channel answers no request of ours, so [RfcommChannel] has no id for
     * it at the moment its first frame arrives — an id comparison hands the phone's own multiplexer handshake
     * to the SDP half, which queues it as the answer to a query nobody made and leaves the dial unanswered.
     */
    private fun onChannelData(cid: Int, psm: Int, data: ByteArray) {
        if (psm == L2capCodec.PSM_RFCOMM) rfcomm.onData(cid, data) else sdp.onData(cid, data)
    }

    private fun onRfcommData(data: ByteArray) {
        val sink = stream
        if (sink == null) {
            // The phone took up the record F published and started talking on that channel. Nothing has opened
            // an iAP2 session against it, so the bytes would go nowhere and say nothing about going there.
            onDiagnostic("adapter-bt: the iPhone sent ${data.size} bytes on RFCOMM with no iAP2 session open")
            return
        }
        sink.onData(data)
    }

    private fun onRfcommPeerEnded() {
        stream?.onPeerEnded()
    }

    private fun writeScan(value: Int): Boolean {
        val result = controller.command(HciCommands.writeScanEnable(value), IDLE_COMMAND_TIMEOUT_MILLIS)
        val accepted = result is HciCommandResult.Complete || result == HciCommandResult.Status(0)
        if (!accepted) onDiagnostic("adapter-bt: write-scan-enable rejected")
        return accepted
    }

    private fun onPairingState(state: PairingState, reason: String) {
        when (state) {
            PairingState.PAIRING -> transition(AdapterBluetoothState.PAIRING, reason)
            PairingState.STALLED -> transition(AdapterBluetoothState.PAIRING_STALLED, reason)
            PairingState.FAILED -> transition(AdapterBluetoothState.FAILED, reason)
            else -> Unit
        }
    }

    private fun transition(state: AdapterBluetoothState, reason: String) {
        try {
            onState(state, reason)
        } catch (_: Exception) {
            // A UI callback cannot be allowed to break the stack.
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MILLIS = 15_000L
        const val CONNECT_POLL_MILLIS = 10L
        const val ALLOW_ROLE_SWITCH = 0x01
        const val IDLE_COMMAND_TIMEOUT_MILLIS = 1_500L

        /** Remote User Terminated Connection. */
        const val REMOTE_USER_TERMINATED_CONNECTION = 0x13
    }
}
