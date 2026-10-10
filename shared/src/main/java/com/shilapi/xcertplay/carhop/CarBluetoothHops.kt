package com.shilapi.xcertplay.carhop

import android.content.Context
import com.shilapi.xcertplay.transport.Iap2BluetoothLink

/**
 * What [CarBluetoothHop] has to do on top of what the connection page asks of it: open the session's
 * Bluetooth leg. Internal because [Iap2BluetoothLink] is, and only the transport ever needs it.
 */
internal interface CarBluetoothTransportHop : CarBluetoothHop {
    /** Opens the iAP2 stream to [address]. Throws [java.io.IOException] on failure. */
    fun link(
        context: Context,
        configuredAddress: String?,
        localAddress: String?,
        onDiagnostic: (String) -> Unit,
    ): Iap2BluetoothLink
}

/**
 * Finds this build's Bluetooth hop, if it has one.
 *
 * The implementation is named here and loaded by name rather than compiled in, so that one source tree
 * serves both: a build that puts the class on the classpath gets the hop, and every other build — the
 * tree as published, CI, a release without it — loads nothing and stays on the AOSP stack. Naming a
 * class that nothing defines is the ordinary case, not an error.
 */
object CarBluetoothHops {
    private const val VENDOR_HOP_CLASS = "com.shilapi.xcertplay.carhop.VendorCarHop"

    @Volatile
    private var resolved = false

    @Volatile
    private var hop: CarBluetoothHop? = null

    /** This build's hop, or null when it has none. */
    val active: CarBluetoothHop?
        get() {
            if (!resolved) load()
            return hop
        }

    /** This build's hop, when it can open the session's Bluetooth leg as well. */
    internal val transport: CarBluetoothTransportHop?
        get() = active as? CarBluetoothTransportHop

    @Synchronized
    private fun load() {
        if (resolved) return
        hop = try {
            Class.forName(VENDOR_HOP_CLASS).getDeclaredConstructor().newInstance() as CarBluetoothHop
        } catch (_: ClassNotFoundException) {
            null
        }
        resolved = true
    }
}
