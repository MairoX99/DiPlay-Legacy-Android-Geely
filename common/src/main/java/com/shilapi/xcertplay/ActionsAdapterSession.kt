package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.shilapi.xcertplay.transport.hci.ActionsHciHost
import com.shilapi.xcertplay.transport.hci.AdapterBluetoothState
import com.shilapi.xcertplay.transport.hci.UsbHciTransport
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the Bluetooth radio's USB interface for as long as the connection page is alive: permission, opening,
 * claiming, and handing an [ActionsHciHost] to whoever needs it.
 *
 * Registers its own receivers the way [com.shilapi.xcertplay.transport.IphoneUsbHost] does, so the activity only
 * has to ask for the session and close it.
 */
internal class ActionsAdapterSession(
    context: Context,
    private val onState: (AdapterBluetoothState, String) -> Unit,
    private val onDiagnostic: (String) -> Unit,
) : Closeable {
    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionAction = "${appContext.packageName}.BLUETOOTH_RADIO_PERMISSION"

    private var connection: UsbDeviceConnection? = null
    private var claimedInterfaces: MutableList<UsbInterface> = mutableListOf()
    private var receivers: MutableList<Closeable> = mutableListOf()

    /** True once claiming the interface has failed: report once, never retry. */
    private var claimFailed = false

    /** The last request from the page. A permission grant that arrives later uses this. */
    private var wantDiscoverable = true

    /** Set on the session thread after a start attempt has finished, including one that failed. */
    @Volatile var bringUpFinished = false
        private set

    private var lastState = AdapterBluetoothState.IDLE
    private var lastReason = "start"

    /**
     * True once the user refused the permission dialog, or the radio refused to start. The connection page asks
     * again on every list refresh (every couple of seconds), so without this the dialog repeats forever and each
     * attempt writes another line into the handshake log.
     */
    private var settled = false

    private val disposed = AtomicBoolean(false)
    private val sessionThread = AtomicReference<Thread?>(null)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "adapter-bt-session").apply {
            isDaemon = true
            sessionThread.set(this)
        }
    }

    @Volatile var host: ActionsHciHost? = null
        private set

    init {
        receivers += registerReceiver(IntentFilter(permissionAction)) { intent ->
            val device = intent.usbDevice() ?: return@registerReceiver
            if (!isRadio(device)) return@registerReceiver
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                worker.execute { open(device) }
            } else {
                onDiagnostic("adapter-bt: USB permission was denied")
                settled = true
                settle(AdapterBluetoothState.FAILED, "usb-permission-denied")
            }
        }
        receivers += registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)) { intent ->
            val device = intent.usbDevice() ?: return@registerReceiver
            if (isRadio(device)) {
                onDiagnostic("adapter-bt: adapter unplugged")
                worker.execute {
                    settled = false
                    claimFailed = false
                    closeOnWorker()
                    settle(AdapterBluetoothState.IDLE, "adapter-unplugged")
                }
            }
        }
    }

    /**
     * Opens the radio if it is present and permitted; asks for permission otherwise.
     * [discoverable] false brings the radio up without inquiry or page scan.
     */
    fun ensureOpen(discoverable: Boolean = true): ActionsHciHost? {
        wantDiscoverable = discoverable
        if (disposed.get()) return host
        // Reset, naming and scan all wait on the stick. Doing that on the UI thread freezes the page.
        if (onSessionThread()) return ensureOpenOnWorker()
        worker.execute { ensureOpenOnWorker() }
        return host
    }

    fun requestPermission(device: UsbDevice): PermissionRequest {
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(appContext, 0, intent, flags),
        )
        return PermissionRequest.Requested(device)
    }

    override fun close() {
        if (onSessionThread()) closeOnWorker() else worker.execute { closeOnWorker() }
    }

    /** Unregisters the receivers and releases the stick without waiting. The page calls this as it goes away. */
    fun dispose() {
        disposed.set(true)
        unregisterReceivers()
        worker.execute { closeOnWorker() }
        // The worker's thread factory closes over this session, and a single-thread executor's thread never
        // times out, so without this the thread outlives the page and keeps its activity reachable.
        worker.shutdown()
    }

    /**
     * Releases the stick and returns after that release.
     * The next screen claims the same interface, so it has to wait, but not on the UI thread:
     * a start still running would freeze the page for as long as the stick takes to answer.
     */
    fun disposeAndWait() {
        disposed.set(true)
        unregisterReceivers()
        if (onSessionThread()) {
            closeOnWorker()
        } else {
            val released = runCatching {
                worker.submit { closeOnWorker() }.get(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            }
            // The next screen claims this same interface. A release that ran out of time can make that
            // claim fail, and without this line the only sign is a page that will not start.
            if (released.isFailure) {
                onDiagnostic("adapter-bt: the radio did not finish releasing in time")
            }
        }
        worker.shutdown()
    }

    private fun ensureOpenOnWorker(): ActionsHciHost? {
        if (disposed.get()) return null
        host?.let { running ->
            running.setDiscoverable(wantDiscoverable)
            return running
        }
        if (claimFailed || settled) return null
        val device = UsbBluetoothRadios.find(appContext) ?: run {
            settle(AdapterBluetoothState.IDLE, "adapter-unplugged")
            return null
        }
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return null
        }
        return open(device)
    }

    private fun open(device: UsbDevice): ActionsHciHost? {
        if (disposed.get() || host != null) return host
        val endpoints = UsbBluetoothRadios.endpoints(device) ?: run {
            onDiagnostic("adapter-bt: ${UsbBluetoothRadios.describe(device)} exposes no HCI endpoint set")
            claimFailed = true
            settle(AdapterBluetoothState.FAILED, "endpoints")
            return null
        }
        val opened = usbManager.openDevice(device) ?: run {
            onDiagnostic("adapter-bt: could not open the radio")
            settle(AdapterBluetoothState.FAILED, "open-device")
            return null
        }
        if (!claim(opened, device, endpoints.interfaces)) {
            opened.close()
            claimFailed = true
            onDiagnostic("adapter-bt: could not claim the radio's HCI interface; not retrying")
            settle(AdapterBluetoothState.FAILED, "claim-interface")
            return null
        }
        onDiagnostic("adapter-bt: radio ${UsbBluetoothRadios.describe(device)}")

        connection = opened
        claimedInterfaces = endpoints.interfaces.toMutableList()
        val transport = UsbHciTransport(
            opened,
            endpoints.interfaces,
            endpoints.eventIn,
            endpoints.aclIn,
            endpoints.aclOut,
            endpoints.commandOut,
        )
        val created = ActionsHciHost(
            transport,
            File(appContext.filesDir, LINK_KEY_FILE),
            ::rememberState,
            onDiagnostic,
        )
        host = created
        if (!created.start(wantDiscoverable)) {
            settled = true
            closeOnWorker()
        }
        // start() already reported the step. This second report is the one that says the attempt is finished,
        // so the connection page can move on without having blocked while the stick answered.
        settle(lastState, lastReason)
        return host
    }

    private fun rememberState(state: AdapterBluetoothState, reason: String) {
        lastState = state
        lastReason = reason
        onState(state, reason)
    }

    /** The attempt is over. Later reads of [bringUpFinished] see that before the callback runs. */
    private fun settle(state: AdapterBluetoothState, reason: String) {
        lastState = state
        lastReason = reason
        bringUpFinished = true
        onState(state, reason)
    }

    private fun closeOnWorker() {
        host?.let { created ->
            host = null
            runCatching { created.close() }
        }
        claimedInterfaces.forEach { claimed ->
            runCatching { connection?.releaseInterface(claimed) }
        }
        claimedInterfaces = mutableListOf()
        connection?.let { opened ->
            connection = null
            runCatching { opened.close() }
        }
    }

    private fun unregisterReceivers() {
        val current = receivers
        receivers = mutableListOf()
        current.forEach { runCatching { it.close() } }
    }

    private fun onSessionThread() = Thread.currentThread() === sessionThread.get()

    /** Claims every interface the radio needs; some radios keep ACL on a second one. */
    private fun claim(
        opened: UsbDeviceConnection,
        device: UsbDevice,
        interfaces: List<UsbInterface>,
    ): Boolean {
        for (usbInterface in interfaces) {
            if (opened.claimInterface(usbInterface, true)) continue
            // Same second attempt the probe makes: select the configuration, then claim again.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && device.configurationCount > 0) {
                runCatching { opened.setConfiguration(device.getConfiguration(0)) }
            }
            if (!opened.claimInterface(usbInterface, true)) return false
        }
        return true
    }

    private fun isRadio(device: UsbDevice): Boolean = UsbBluetoothRadios.isCandidate(
        device.vendorId,
        device.productId,
        UsbBluetoothRadios.shapesOf(device),
    )

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        return Closeable { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val LINK_KEY_FILE = "adapter-linkkeys.tsv"
        const val CLOSE_WAIT_MILLIS = 45_000L
    }
}
