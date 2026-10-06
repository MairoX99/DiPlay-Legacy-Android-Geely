package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Takes the steering wheel's media keys off the head unit before it acts on them.
 *
 * A car's window manager sees the wheel first and can swallow the press, so DiPlay is left with no
 * key at all no matter how its media session is registered — that is the failure a Geely head unit
 * shows. A service that asks to filter key events sits earlier in the input pipeline, so it sees the
 * press first and may keep it.
 *
 * This is the generic Android path and needs no vendor SDK, so it also decides whether the ECARX
 * input service in [EcarxKeyInterceptor] is needed on this head unit at all: whichever path produces
 * the logged press is the one this car uses. Keys DiPlay does not use pass through untouched, so
 * volume and every other key keep working.
 *
 * Nothing here runs until the driver switches the service on in the accessibility settings.
 */
class WheelKeyService : AccessibilityService() {
    override fun onServiceConnected() {
        Log.i(TAG, "wheel key service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        return CarPlayMediaKeys.dispatchFromWheel(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "DiPlay-WheelKeys"

        /** Whether the driver has switched this service on. Safe to call from any thread. */
        fun isEnabled(context: Context): Boolean = try {
            val component = ComponentName(context, WheelKeyService::class.java)
            // Some ROMs store the shortened ".WheelKeyService" spelling, so both are accepted.
            val ours = setOf(component.flattenToString(), component.flattenToShortString())
            Settings.Secure
                .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
                .split(':')
                .any { entry -> ours.any { it.equals(entry.trim(), ignoreCase = true) } }
        } catch (error: Exception) {
            Log.w(TAG, "could not read the accessibility service list", error)
            false
        }
    }
}
