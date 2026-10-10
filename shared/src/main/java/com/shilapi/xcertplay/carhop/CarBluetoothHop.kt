package com.shilapi.xcertplay.carhop

import android.content.Context

/** A phone the head unit's own Bluetooth service offers for a session. */
data class CarBluetoothPhone(val address: String, val name: String)

/**
 * The head unit's own Bluetooth, for head units whose stack an app cannot drive.
 *
 * Where the radios and the pairings belong to a vendor service there is nothing in `BluetoothAdapter`
 * to list and no bonded device to open a socket to, so the Bluetooth leg of a wireless session has to
 * come from somewhere else. A build that has that elsewhere supplies it here; every other build has
 * none of this and is not worse off for it — [CarBluetoothHops.active] is null, the connection page
 * lists the AOSP pairings, and the session runs on the AOSP stack.
 *
 * The interface is the whole of what the public side knows: which head units need it, what phones the
 * head unit offers, whether it could start now, and the two lines the connection page shows.
 */
interface CarBluetoothHop {
    /** True when this head unit needs this hop rather than the AOSP stack. */
    fun applies(context: Context): Boolean

    /** Whether a session could start right now. May cost a round trip; callers cache the answer. */
    fun ready(context: Context): Boolean

    /** The phones the head unit reports. Blocking — callers stay off the main thread. */
    fun connectedPhones(context: Context): List<CarBluetoothPhone>

    /** What turning this hop on does to the head unit, or null when there is nothing to say. */
    fun note(context: Context): String?

    /** Whether the hop can start, or null when there is nothing to say. */
    fun status(context: Context): String?

    /** Hands the head unit's radio back once the session is over. */
    fun idleAfterSession(context: Context)
}
