package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The page owns the only session log, so whether it lends one decides whether transport lines exist.
 *
 * Driven through the page's own methods rather than `controller.setup()`: `onCreate` returns before
 * `initializeSessionLog()` whenever `DiPlayBootstrap.ensure` fails, and it always fails under test
 * because `assets/offline-mfi` is deliberately not in the tree — no adb, no head unit, no credentials.
 * The bootstrap has nothing to do with this wiring, so the test enters after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayHostLogWiringTest {
    private fun call(activity: CarPlayHostActivity, name: String) {
        CarPlayHostActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }

    @Test
    fun openingTheHostPageLendsTheLogToTheTransportThreads() {
        DiagSink.detach()
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        call(activity, "initializeSessionLog")
        assertTrue("the page must lend its session log", DiagSink.isAttached())
        call(activity, "onDestroy")
        assertFalse("a destroyed page must stop accepting lines", DiagSink.isAttached())
    }
}
