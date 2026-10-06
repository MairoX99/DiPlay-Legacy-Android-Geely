package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method

/**
 * The attempts to make when switching the car hotspot on, in order. A single-radio firmware cannot
 * hold the access point while station mode owns the radio, so the last attempt releases it first;
 * when station mode is already off there is nothing left to try after the first.
 */
internal fun hotspotEnableAttempts(stationEnabled: Boolean): List<Boolean> =
    if (stationEnabled) listOf(false, true) else listOf(false)

/**
 * Switches the head unit's own Wi-Fi hotspot on.
 *
 * DiPlay used to only read the hotspot state ([CarHotspotStatus]) and leave the driver to turn it
 * on in the car settings, because enabling it needs a permission many firmwares grant only over ADB.
 * This attempts the switch anyway and reports why it could not, so the caller can keep falling back
 * to the old message rather than failing differently.
 *
 * The AP methods are reached reflectively only because compileSdk no longer exposes them; on this
 * project's API 19 floor `setWifiApEnabled` is the platform's own public method. No vendor interface
 * is used — ECARX's own AP service belongs to head units newer than this fork targets.
 */
internal class CarHotspotController(context: Context) {
    private val appContext: Context = context.applicationContext ?: context
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val setApEnabled: Method? = wifi?.let {
        runCatching {
            WifiManager::class.java.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull()
    }

    /**
     * Turns the hotspot on when it is off and waits for the firmware to report it enabled. Returns
     * null once it is on, or the reason it could not be switched on.
     */
    fun ensureEnabled(timeoutMillis: Long): String? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (CarHotspotStatus.isEnabled(appContext) == true) return null
        val wifi = wifi ?: return "there is no Wi-Fi service"
        val setAp = setApEnabled ?: return "this firmware does not expose the hotspot API"

        for (releaseStation in hotspotEnableAttempts(stationEnabled = wifi.isWifiEnabled)) {
            if (releaseStation) wifi.isWifiEnabled = false
            if (enableAccessPoint(setAp, wifi) && awaitEnabled(timeoutMillis)) return null
        }
        return "this firmware refused to switch the hotspot on, or did not report it in time"
    }

    /**
     * Passes a null configuration on purpose: it keeps whatever the car already has, where building
     * one would rewrite the head unit's own hotspot settings behind the driver's back.
     */
    private fun enableAccessPoint(setAp: Method, wifi: WifiManager): Boolean = try {
        setAp.invoke(wifi, null, true) as Boolean
    } catch (error: ReflectiveOperationException) {
        Log.w(TAG, "The hotspot API rejected the call", error)
        false
    } catch (error: RuntimeException) {
        // A SecurityException here means the firmware gates the permission, which is the usual case.
        Log.w(TAG, "The hotspot API rejected the call", error)
        false
    }

    private fun awaitEnabled(timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (CarHotspotStatus.isEnabled(appContext) == true) return true
            SystemClock.sleep(STATE_POLL_MILLIS)
        }
        return CarHotspotStatus.isEnabled(appContext) == true
    }

    private companion object {
        const val TAG = "DiPlay-CarHotspot"
        const val STATE_POLL_MILLIS = 250L
    }
}
