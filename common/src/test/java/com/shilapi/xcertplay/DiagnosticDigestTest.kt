package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDigestTest {
    private fun section(name: String, vararg lines: String, newest: Boolean = false) =
        DiagnosticSection(name, lines.toList(), newest)

    @Test fun repeatedLinesCostOneLinePlusACount() {
        val collapsed = DiagnosticDigest.collapseRuns(listOf("Texture surface created", "Texture surface created", "Texture surface created"))
        assertEquals(listOf("Texture surface created (×3)"), collapsed)
    }

    @Test fun aRunOfDifferentLinesIsLeftAlone() {
        val lines = listOf("a", "b", "a")
        assertEquals(lines, DiagnosticDigest.collapseRuns(lines))
    }

    @Test fun signalLinesOutliveRoutineOnesWhenTheBudgetBinds() {
        val routine = (1..4000).map { "09:00:00.000  routine line $it padding padding padding" }
        val report = DiagnosticDigest.compose(
            header = "DiPlay 0.3.8\n",
            sections = listOf(section("diplay.log", *routine.toTypedArray(), "09:00:01.000  STEP usb/wait: device not present", "09:00:02.000  ERROR usb: claim failed", newest = true)),
            budgetBytes = 4096,
        )
        assertTrue(report.contains("STEP usb/wait: device not present"))
        assertTrue(report.contains("ERROR usb: claim failed"))
        assertTrue(report.contains("bytes of older log omitted") || report.contains("lines omitted"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 4096)
    }

    @Test fun theNewestRunBeatsAnOlderOneForTheRemainingRoom() {
        val old = (1..200).map { "09:00:0$it  old routine" }
        val new = (1..200).map { "09:10:0$it  new routine" }
        val report = DiagnosticDigest.compose(
            "H\n",
            listOf(section("previous.log", *old.toTypedArray()), section("diplay.log", *new.toTypedArray(), newest = true)),
            budgetBytes = 1200,
        )
        assertTrue(report.contains("new routine"))
        assertTrue("an older run must yield to the newest one", !report.contains("old routine"))
    }

    @Test fun theHeaderAlwaysSurvives() {
        val report = DiagnosticDigest.compose(
            header = "DiPlay 0.3.8 · API 22 · alps E01\n",
            sections = listOf(section("diplay.log", *Array(5000) { "x".repeat(80) }, newest = true)),
            budgetBytes = 2048,
        )
        assertTrue(report.startsWith("DiPlay 0.3.8"))
        assertTrue(report.contains("alps E01"))
    }

    @Test fun anEmptyReportIsStillAReport() {
        val report = DiagnosticDigest.compose("H\n", emptyList(), budgetBytes = 1024)
        assertEquals("H\n", report)
    }

    @Test fun aHeaderLargerThanTheBudgetIsTruncatedNotThrown() {
        val report = DiagnosticDigest.compose("y".repeat(9000), emptyList(), budgetBytes = 1024)
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 1024)
    }

    @Test fun omittedCountsAreStatedSoAMissingLineIsReadable() {
        val lines = (1..500).map { "09:00:00.000  routine $it" }
        val report = DiagnosticDigest.compose("H\n", listOf(section("diplay.log", *lines.toTypedArray(), newest = true)), budgetBytes = 700)
        assertTrue(report.contains("omitted"))
    }

    /** The tiering must not become a way to overflow: signals are first, not exempt from the budget. */
    @Test fun aRunOfNothingButSignalLinesIsStillBounded() {
        val signals = (1..500).map { "09:00:00.000  ERROR device $it rejected" }
        val report = DiagnosticDigest.compose("H\n", listOf(section("diplay.log", *signals.toTypedArray(), newest = true)), budgetBytes = 800)
        assertTrue(report.contains("omitted"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= 800)
    }

    /** A budget too small for even the reserve must return text, not throw. */
    @Test fun aBudgetSmallerThanTheNoteReserveStillReturnsText() {
        val report = DiagnosticDigest.compose("H\n", listOf(section("diplay.log", "ERROR x", newest = true)), budgetBytes = 8)
        assertTrue(report.startsWith("H"))
    }

    /**
     * The field report was four near-identical runs. Ordering by a per-section index instead of by
     * position in the whole report reads them column-wise — every run's first line together, then every
     * run's second — so the timestamps run backwards. A reader has to be able to read it top to bottom.
     */
    @Test fun olderRunsStayAboveNewerOnesWhenEverythingFits() {
        val older = listOf("09:00:01.000  Starting CarPlay controller", "09:00:02.000  STEP usb/discover: searching")
        val newer = listOf("09:10:01.000  Starting CarPlay controller", "09:10:02.000  STEP usb/discover: searching")
        val report = DiagnosticDigest.compose(
            "H\n",
            listOf(
                section("previous.log", *older.toTypedArray()),
                section("diplay.log", *newer.toTypedArray(), newest = true),
            ),
            budgetBytes = 100_000,
        )
        val lines = report.lines().filter { it.contains("CarPlay controller") || it.contains("usb/discover") }
        assertEquals(
            listOf(older[0], older[1], newer[0], newer[1]),
            lines,
        )
    }

    /** With eight runs in one report, a reader has to be able to see where one ends and the next begins. */
    @Test fun eachRunThatContributesLinesIsLabelled() {
        val report = DiagnosticDigest.compose(
            "H\n",
            listOf(
                section("previous.log", "09:00:01.000  older line"),
                section("diplay.log", "09:10:01.000  newer line", newest = true),
            ),
            budgetBytes = 100_000,
        )
        assertTrue(report.contains("--- previous.log ---"))
        assertTrue(report.contains("--- diplay.log ---"))
    }

    /**
     * `audio stats …` and `video stats …` repeat every second. Keeping them all spends the budget on
     * counters nobody asked about; the header's own timestamps already say the run was steady. Keyed past
     * the timestamp, one line per kind survives.
     */
    @Test fun periodicCountersCostOneLinePerKindNotOnePerSecond() {
        val stats = (1..200).map { "09:39:${"%02d".format(it % 60)}.000  audio stats audioType=media rx=$it dropped=0" }
        val report = DiagnosticDigest.compose(
            "H\n",
            listOf(section("diplay.log", *stats.toTypedArray(), newest = true)),
            budgetBytes = 200_000,
        )
        assertEquals("one line per kind, not one per second", 1, report.lines().count { it.contains("audio stats") })
    }
}
