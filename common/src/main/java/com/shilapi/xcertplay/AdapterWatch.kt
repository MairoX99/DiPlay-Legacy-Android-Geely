package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.transport.AdapterObservation
import com.shilapi.xcertplay.transport.hci.ActionsHciHost
import com.shilapi.xcertplay.transport.hci.AdapterBluetoothState
import java.io.Closeable

/**
 * Starts the USB Bluetooth adapter and keeps the latest startup step.
 * The connection page must not open a second session while this one still holds the interface.
 */
internal class AdapterWatch(
    context: Context,
    private val onChanged: (AdapterObservation) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var refreshing = false

    @Volatile private var lastState: AdapterBluetoothState? = null
    @Volatile private var lastReason: String? = null
    @Volatile private var published: AdapterObservation? = null

    private val session = ActionsAdapterSession(
        appContext,
        { state, reason ->
            // The line the host would write for the same transition, recorded here as well: this session is the
            // one the pairing happens in, and it is gone — with its log truncated under it — by the time a run
            // starts. See AdapterDiagnostics.
            AdapterDiagnostics.record("adapter-bt: $state ($reason)")
            lastState = state
            lastReason = reason
            if (refreshing) return@ActionsAdapterSession
            publish()
        },
        AdapterDiagnostics::record,
    )

    fun refresh(discoverable: Boolean = true): AdapterObservation {
        refreshing = true
        return try {
            if (UsbBluetoothRadios.find(appContext) != null) session.ensureOpen(discoverable)
            snapshot().also { published = it }
        } finally {
            refreshing = false
        }
    }

    fun host(): ActionsHciHost? = session.host

    override fun close() {
        session.dispose()
    }

    /** Blocks until the stick is released. Call this off the UI thread. */
    fun closeAndWait() {
        session.disposeAndWait()
    }

    private fun publish() {
        val next = snapshot()
        if (next == published) return
        published = next
        if (Looper.myLooper() == Looper.getMainLooper()) onChanged(next)
        else main.post { onChanged(next) }
    }

    private fun snapshot(): AdapterObservation {
        val device = UsbBluetoothRadios.find(appContext)
        val running = session.host
        return AdapterObservation(
            plugged = device != null,
            permissionGranted = device != null && (usb.hasPermission(device) || running != null),
            state = lastState,
            reason = lastReason,
            paired = running?.pairedTarget() != null,
        )
    }
}
