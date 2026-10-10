package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric because the facade's whole job is to call [android.util.Log] and then hand the same line to
 * the sink: under plain JUnit the platform call throws "not mocked" and the test would never reach the
 * behaviour it is checking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiagLogTest {
    @Before fun clear() = DiagSink.reset()

    @Test fun everyLevelReachesTheSinkTaggedWithItsSource() {
        val written = mutableListOf<String>()
        DiagSink.attach { written.add(it) }
        DiagLog.d("xcertplay-usb", "detail")
        DiagLog.i("xcertplay-usb", "info")
        DiagLog.w("xcertplay-usb", "warn")
        DiagLog.e("xcertplay-usb", "error")
        assertEquals(
            listOf(
                "xcertplay-usb: detail",
                "xcertplay-usb: info",
                "xcertplay-usb: warn",
                "xcertplay-usb: error",
            ),
            written,
        )
    }

    @Test fun aThrowableContributesItsClassAndMessageButNotItsTrace() {
        val written = mutableListOf<String>()
        DiagSink.attach { written.add(it) }
        DiagLog.w("usb", "claim failed", IllegalStateException("no endpoint pair"))
        assertEquals(1, written.size)
        assertTrue(written.single().contains("claim failed"))
        assertTrue(written.single().contains("IllegalStateException"))
        assertTrue(written.single().contains("no endpoint pair"))
        assertTrue("a stack trace belongs to the crash handler", !written.single().contains("\tat "))
    }

    @Test fun writingNeverBlocksOnADisk() {
        // No writer attached: the call must fall into the held buffer and return.
        DiagLog.i("usb", "before the page opened")
        val written = mutableListOf<String>()
        DiagSink.attach { written.add(it) }
        assertEquals(listOf("usb: before the page opened"), written)
    }
}
