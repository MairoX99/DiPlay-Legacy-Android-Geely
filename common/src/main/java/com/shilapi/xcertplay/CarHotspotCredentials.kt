package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.CarHotspotAccessPoint
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/**
 * The manual-hotspot credentials a connection should run with. A stored SSID means the driver has
 * been here and their values are the ones to use; only a blank one is filled from the car's own
 * hotspot, whose name and key the firmware already holds.
 *
 * The pair is filled together, never field by field: keeping a passphrase the driver cleared for an
 * open hotspot would name a security mode and a key that the wireless path refuses to pair.
 */
internal fun manualHotspotCredentials(
    storedSsid: String,
    storedPassphrase: String,
    carHotspot: CarHotspotAccessPoint?,
): Pair<String, String> {
    if (storedSsid.isNotBlank() || carHotspot == null) return storedSsid to storedPassphrase
    return carHotspot.ssid to carHotspot.passphrase.orEmpty()
}

/**
 * The same pair, read from storage and from the head unit.
 *
 * Every screen that names the hotspot the driver is being asked for has to ask this one question.
 * Reading storage alone is what let the home screen name a hotspot it had no name for, and refuse a
 * connection whose credentials the car had already supplied.
 */
internal fun savedOrCarHotspot(context: Context): Pair<String, String> =
    manualHotspotCredentials(
        storedSsid = AirPlayPersistence.loadManualHotspotSsid(context),
        storedPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
        carHotspot = CarHotspotStatus.accessPoint(context),
    )

/**
 * The security mode the effective pair runs with.
 *
 * A pair the driver saved carries the mode they chose with it. A pair taken from the car carries no
 * choice at all, and storage derives one from the passphrase the driver never saved — so a WPA2 car
 * key is announced as an open network. Both the settings page and the hotspot manager hold the
 * announced mode against the live access point and refuse the mismatch, which makes the driver's
 * first run on a fresh install the one that fails.
 */
internal fun manualHotspotSecurityFor(
    storedSsid: String,
    storedSecurity: ManualHotspotSecurity,
    usedPassphrase: String,
): ManualHotspotSecurity =
    if (storedSsid.isNotBlank()) storedSecurity
    else ManualHotspotValidation.securityFor(usedPassphrase)
