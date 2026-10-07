package com.shilapi.xcertplay

import android.os.Build

/**
 * Whether this head unit can carry CarPlay over Wi-Fi at all.
 *
 * Wireless CarPlay runs its iAP2 leg over a Bluetooth RFCOMM socket. Geely's E01 (FS11GQJ)
 * hands third-party apps no such socket: the AOSP stack it ships with has the socket classes
 * removed, the ECARX facade declares the interface without an implementation, and the GOC SDK's
 * SPP entry points are hardcoded stubs. The transport cannot be established there, so the UI
 * offers USB only. Every other head unit keeps the wireless path.
 *
 * Read per call, so the answer never outlives a head-unit change, like
 * [com.shilapi.xcertplay.media.NavigationAudioStream.deviceDefault].
 */
object WirelessCarPlay {
    /** Head units whose Bluetooth stack leaves no socket for the iAP2 leg. */
    private const val E01 = "FS11GQJ"

    val uiOffered: Boolean
        get() = !Build.DEVICE.equals(E01, ignoreCase = true)
}
