package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredLinkAssessmentTest {
    private fun searching(
        emptyPolls: Int = WiredLinkAssessment.STUCK_POLLS,
        deviceCount: Int = 0,
        detachCycles: Int = 0,
        sessionRan: Boolean = false,
    ) = WiredLinkObservation(
        wired = true,
        step = WiredLinkStep.SEARCHING,
        emptyPolls = emptyPolls,
        deviceCount = deviceCount,
        detachCycles = detachCycles,
        sessionRan = sessionRan,
    )

    /**
     * An empty search can be empty for two reasons, and both read as "waiting for an iPhone" on
     * screen. They are told apart by what is on the bus, and confusing them sends the user after the
     * wrong part — a cable, when what is attached is simply not a phone.
     */
    @Test fun anEmptyBusBlamesThePortAndTheCable() {
        val report = WiredLinkAssessment.assess(searching(deviceCount = 0))

        assertEquals(WiredLinkFinding.NO_USB_DEVICE, report.finding)
        assertEquals(WiredLinkStep.SEARCHING, report.step)
    }

    @Test fun aNonAppleDeviceOnTheBusIsNotACableFault() {
        val report = WiredLinkAssessment.assess(searching(deviceCount = 1))

        assertEquals(WiredLinkFinding.NOT_APPLE_DEVICE, report.finding)
    }

    /**
     * A search that has been stuck for the threshold always has an explanation, because "stuck" means
     * the bus holds no device bring-up can use — and that is one of exactly two things.
     */
    @Test fun aStuckSearchAlwaysSaysWhichOfTheTwoItIs() {
        val findings = listOf(
            searching(deviceCount = 0),
            searching(deviceCount = 2),
        ).map { WiredLinkAssessment.assess(it).finding }

        assertEquals(
            listOf(WiredLinkFinding.NO_USB_DEVICE, WiredLinkFinding.NOT_APPLE_DEVICE),
            findings,
        )
    }

    /** A user who plugged in two seconds ago has not finished being patient, let alone being stuck. */
    @Test fun aSearchYoungerThanTheThresholdIsNotAFindingYet() {
        val report = WiredLinkAssessment.assess(
            searching(emptyPolls = WiredLinkAssessment.STUCK_POLLS - 1, deviceCount = 0),
        )

        assertEquals(WiredLinkFinding.NONE, report.finding)
        assertFalse(report.worthReporting)
    }

    /**
     * Cycles are counted the same either side of a session; what they mean is not. Before a session
     * ran nothing was drawing current, so a supply dip explains nothing and the plug does. After one
     * ran, the current the phone was pulling is exactly what a dip would interrupt.
     */
    @Test fun theSameCyclesMeanALoosePlugBeforeASessionAndADipAfterOne() {
        val loose = WiredLinkAssessment.assess(
            searching(detachCycles = WiredLinkAssessment.LOOSE_CONTACT_CYCLES, sessionRan = false),
        )
        val dip = WiredLinkAssessment.assess(
            searching(detachCycles = WiredLinkAssessment.SUPPLY_DIP_CYCLES, sessionRan = true),
        )

        assertEquals(WiredLinkFinding.LOOSE_CONTACT, loose.finding)
        assertEquals(WiredLinkFinding.SUPPLY_DIP, dip.finding)
    }

    /** A dip is the more specific verdict, so it wins over "the bus is empty" — it just became empty. */
    @Test fun cyclesAreWeighedBeforeWhatIsOnTheBus() {
        val report = WiredLinkAssessment.assess(
            searching(
                deviceCount = 0,
                detachCycles = WiredLinkAssessment.SUPPLY_DIP_CYCLES,
                sessionRan = true,
            ),
        )

        assertEquals(WiredLinkFinding.SUPPLY_DIP, report.finding)
    }

    /**
     * A dip is counted at the moment the phone comes back, and the phone is back — so the rung then is
     * past the search, not on it. A finding gated on searching would therefore never be reachable at
     * all, which is what makes this ordering load-bearing rather than cosmetic.
     */
    @Test fun aDipIsReportedOnTheRungThePhoneComesBackTo() {
        val report = WiredLinkAssessment.assess(
            searching(detachCycles = WiredLinkAssessment.SUPPLY_DIP_CYCLES, sessionRan = true)
                .copy(step = WiredLinkStep.CARPLAY_CONFIGURATION),
        )

        assertEquals(WiredLinkFinding.SUPPLY_DIP, report.finding)
    }

    @Test fun cyclesBelowTheThresholdAreNotAFinding() {
        val report = WiredLinkAssessment.assess(
            searching(emptyPolls = 0, detachCycles = 1, sessionRan = false),
        )

        assertEquals(WiredLinkFinding.NONE, report.finding)
    }

    /** Two quick cycles before a session is not yet the loose plug that three of them are. */
    @Test fun twoQuickCyclesBeforeASessionAreNotYetALoosePlug() {
        val report = WiredLinkAssessment.assess(
            searching(emptyPolls = 0, detachCycles = 2, sessionRan = false),
        )

        assertEquals(WiredLinkFinding.NONE, report.finding)
    }

    /** The wireless hop has no wired link to assess, and a report about one would be fiction. */
    @Test fun aRouteThatIsNotWiredIsNotAssessed() {
        val report = WiredLinkAssessment.assess(searching(deviceCount = 0).copy(wired = false))

        assertEquals(WiredLinkStep.NOT_WIRED, report.step)
        assertEquals(WiredLinkFinding.NONE, report.finding)
        assertFalse(report.worthReporting)
    }

    /**
     * Every rung past the search has a device, so the bus explains nothing about it. A finding here
     * would be the assessment telling the user to check a cable that is demonstrably working.
     */
    @Test fun aRungPastTheSearchHasNoBusFinding() {
        val steps = listOf(
            WiredLinkStep.CARPLAY_CONFIGURATION,
            WiredLinkStep.DATA_PATHS,
            WiredLinkStep.CONTROL,
        )

        for (step in steps) {
            val report = WiredLinkAssessment.assess(searching(deviceCount = 0).copy(step = step))
            assertEquals("step $step", WiredLinkFinding.NONE, report.finding)
        }
    }

    /** The line carries `usb/` so the handshake log promotes it, and `DIAG ` so it is not a `STEP `. */
    @Test fun theLogLineIsShapedToReachTheHandshakeLog() {
        val line = WiredLinkAssessment.assess(searching(deviceCount = 0)).logLine()

        assertTrue(line, line.contains("usb/"))
        assertTrue(line, line.startsWith("DIAG "))
        assertTrue(line, line.contains("finding=no_usb_device"))
        assertTrue(line, line.contains("polls=${WiredLinkAssessment.STUCK_POLLS}"))
    }
}
