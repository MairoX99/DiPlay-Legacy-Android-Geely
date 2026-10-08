package com.shilapi.xcertplay

/**
 * Wireless CarPlay stays available on every head unit, including the Geely E01 (FS11GQJ).
 *
 * Which of those paths this head unit can run is [DeviceConnectionSupport]. The E01 keeps the
 * wireless entry; Android 9 and older simply have no Wi-Fi Direct choice.
 */
object WirelessCarPlay {
    val uiOffered: Boolean
        get() = true
}
