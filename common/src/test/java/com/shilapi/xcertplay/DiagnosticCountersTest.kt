package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class DiagnosticCountersTest {
    @Before fun clear() = DiagnosticCounters.reset()

    @Test fun aFailedWriteIsCountedRatherThanSwallowed() {
        val folder = Files.createTempDirectory("diplay-write-failure").toFile()
        try {
            // A directory where the log file should be: every write fails, and the old code said nothing.
            val path = folder.resolve("diplay.log")
            path.mkdirs()
            val log = SessionLogFile(path)
            // The reset write fails here too, and the page already tolerates that the same way.
            runCatching { log.reset("started") }
            repeat(3) { log.append("connection state") }
            log.close()
            assertTrue("a write that failed must be counted", DiagnosticCounters.logWriteFailures() > 0)
        } finally { folder.deleteRecursively() }
    }

    @Test fun countersStartAtZeroAndSummarize() {
        assertEquals(0L, DiagnosticCounters.logWriteFailures())
        assertEquals(0L, DiagnosticCounters.logQueueDrops())
        DiagnosticCounters.noteLogQueueDrop()
        DiagnosticCounters.noteLogWriteFailure()
        assertEquals(1L, DiagnosticCounters.logQueueDrops())
        assertEquals(1L, DiagnosticCounters.logWriteFailures())
        assertTrue(DiagnosticCounters.summary().contains("queueDrops=1"))
        assertTrue(DiagnosticCounters.summary().contains("writeFailures=1"))
    }
}
