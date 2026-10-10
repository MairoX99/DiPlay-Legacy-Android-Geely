package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadPosterTest {
    private fun poster(onMainThread: Boolean, posted: MutableList<() -> Unit>) =
        MainThreadPoster(isMainThread = { onMainThread }, post = { posted += it })

    @Test fun anUpdateAlreadyOnTheMainThreadIsAppliedThereAndThen() {
        val posted = mutableListOf<() -> Unit>()
        var applied = 0

        poster(onMainThread = true, posted = posted).run { applied += 1 }

        assertEquals(1, applied)
        assertTrue("nothing is deferred when the caller is already there", posted.isEmpty())
    }

    /** The state that explains a failed attempt arrives on the Bluetooth stack's threads, not on ours. */
    @Test fun anUpdateFromTheBluetoothStackIsPostedInsteadOfAppliedThere() {
        val posted = mutableListOf<() -> Unit>()
        var applied = 0

        poster(onMainThread = false, posted = posted).run { applied += 1 }

        assertEquals("the screen must not be touched from the stack's thread", 0, applied)
        assertEquals(1, posted.size)
        posted.single().invoke()
        assertEquals(1, applied)
    }
}
