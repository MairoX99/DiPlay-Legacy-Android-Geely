package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class NcmWriteWindowTest {
    private val grace = NcmWriteWindow.AUTHORIZATION_GRACE_MILLIS

    @Test fun beforeThePhoneIsAskedAFailureIsAFault() {
        val window = NcmWriteWindow(grace)
        assertEquals(NcmWriteWindow.Verdict.FAULT, window.verdict(0L, sessionLive = false))
        assertEquals(NcmWriteWindow.Verdict.FAULT, window.verdict(grace, sessionLive = true))
    }

    @Test fun whileTheDialogWaitsAFailureIsExpected() {
        val window = NcmWriteWindow(grace)
        window.arm(nowMillis = 1_000L)
        assertEquals(NcmWriteWindow.Verdict.EXPECTED, window.verdict(1_000L, sessionLive = false))
        assertEquals(NcmWriteWindow.Verdict.EXPECTED, window.verdict(1_000L + grace - 1, sessionLive = false))
    }

    @Test fun aLiveSessionEndsTheWindowImmediately() {
        val window = NcmWriteWindow(grace)
        window.arm(nowMillis = 1_000L)
        assertEquals(NcmWriteWindow.Verdict.FAULT, window.verdict(1_500L, sessionLive = true))
    }

    @Test fun aGraceThatPassesWithoutASessionExpires() {
        val window = NcmWriteWindow(grace)
        window.arm(nowMillis = 1_000L)
        assertEquals(NcmWriteWindow.Verdict.EXPIRED, window.verdict(1_000L + grace, sessionLive = false))
    }

    @Test fun theGraceIsMeasuredFromTheFirstAsk() {
        val window = NcmWriteWindow(grace)
        window.arm(nowMillis = 1_000L)
        window.arm(nowMillis = 1_000L + grace)
        assertEquals(NcmWriteWindow.Verdict.EXPIRED, window.verdict(1_000L + grace, sessionLive = false))
    }
}
