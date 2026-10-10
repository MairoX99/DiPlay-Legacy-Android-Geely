package com.shilapi.xcertplay

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android builds an Activity with `newInstance()` and only then calls `attach()`, which is what sets
 * the base context. Every property initializer therefore runs with no context attached, and anything
 * that reads a preference from one takes the whole app down the moment the screen is opened — on both
 * transports, because the crash happens before the screen knows which one it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayHostActivityConstructionTest {
    @Test
    fun theHostActivityIsConstructibleBeforeItsContextIsAttached() {
        CarPlayHostActivity()
    }
}
