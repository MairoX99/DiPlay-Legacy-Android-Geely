package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Plugging a device in must not start a connection. The app used to claim `USB_DEVICE_ATTACHED`, so
 * Android opened the projection screen on plug-in and the wired session started without the user
 * asking — the external adapter, once it was in the same filter, did the same thing. A connection
 * now begins only from the connect button, so no activity may claim the action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbAutoLaunchTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun noActivityClaimsUsbAttach() {
        val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val claimed = context.packageManager.queryIntentActivities(attach, 0).map { it.activityInfo.name }
        assertTrue(
            "plugging a device in must not open a screen by itself; claimed by $claimed",
            claimed.isEmpty(),
        )
    }
}
