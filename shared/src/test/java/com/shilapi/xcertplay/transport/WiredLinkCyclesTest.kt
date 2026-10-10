package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredLinkCyclesTest {
    private val ledger = WiredLinkCycles()
    private val quick = WiredLinkCycles.QUICK_REATTACH_NANOS

    @Test fun aRePlugInsideTheWindowCounts() {
        ledger.noteDetach(nowNanos = 0)
        ledger.noteReattach(nowNanos = quick - 1)

        assertEquals(1, ledger.cycles())
    }

    /** Walking to the phone and plugging it back in is not a loose plug, however often it happens. */
    @Test fun aRePlugOutsideTheWindowDoesNotCount() {
        ledger.noteDetach(nowNanos = 0)
        ledger.noteReattach(nowNanos = quick + 1)

        assertEquals(0, ledger.cycles())
    }

    /**
     * The trap this whole type exists for: a re-enumeration detaches the phone on every connection
     * that works, and the attach that follows must not be read as a re-plug. Nothing armed a detach
     * for it, so it does not count — three good connections in a row would otherwise be enough to
     * call a working cable loose.
     */
    @Test fun aReattachThatNothingAnnouncedIsNotACycle() {
        repeat(5) { ledger.noteReattach(nowNanos = 0) }

        assertEquals(0, ledger.cycles())
        assertFalse(ledger.sessionRan())
    }

    @Test fun onlyTheFirstReattachAfterADetachCounts() {
        ledger.noteDetach(nowNanos = 0)
        ledger.noteReattach(nowNanos = 1)
        ledger.noteReattach(nowNanos = 2)

        assertEquals(1, ledger.cycles())
    }

    @Test fun cyclesAccumulateAcrossRounds() {
        repeat(3) { round ->
            ledger.noteDetach(nowNanos = round * 100L)
            ledger.noteReattach(nowNanos = round * 100L + 1)
        }

        assertEquals(3, ledger.cycles())
    }

    @Test fun aSlowRoundInTheMiddleIsNotCounted() {
        ledger.noteDetach(nowNanos = 0)
        ledger.noteReattach(nowNanos = 1)
        ledger.noteDetach(nowNanos = 2)
        ledger.noteReattach(nowNanos = 2 + quick + 1)
        ledger.noteDetach(nowNanos = 3 * quick)
        ledger.noteReattach(nowNanos = 3 * quick + 1)

        assertEquals(2, ledger.cycles())
    }

    @Test fun aSessionIsRememberedOnceRun() {
        assertFalse(ledger.sessionRan())

        ledger.noteSessionRan()

        assertTrue(ledger.sessionRan())
    }

    /**
     * The rounds are what a verdict is about, so a session from before the first of them answers nothing.
     * Left standing, the session the user just finished would still be recalled when a plug later goes
     * loose, and two reseats would read as a supply dip where three were needed to read as a loose plug.
     */
    @Test fun aSessionFromBeforeTheRoundsDoesNotAnswerForThem() {
        ledger.noteSessionRan()
        ledger.noteDetach(nowNanos = 0)
        ledger.noteReattach(nowNanos = 1)

        assertFalse(ledger.sessionRan())
    }

    /**
     * A verdict is an event: the rounds that proved it are over by the time it is said, so leaving
     * them counted would restate it on every later attach — including after an unplug the user meant.
     *
     * The session goes with them, because the question the two verdicts answer differently is whether
     * a session ran on *these* rounds. A session remembered from an hour ago would keep answering it,
     * and the loose plug — which needs three rounds precisely because nothing was drawing current —
     * would be out of reach for the rest of the process.
     */
    @Test fun reportingAVerdictForgetsTheCyclesAndTheSession() {
        ledger.noteDetach(nowNanos = 0)
        ledger.noteReattach(nowNanos = 1)
        ledger.noteSessionRan()

        ledger.noteReported()

        assertEquals(0, ledger.cycles())
        assertFalse(ledger.sessionRan())
    }
}
