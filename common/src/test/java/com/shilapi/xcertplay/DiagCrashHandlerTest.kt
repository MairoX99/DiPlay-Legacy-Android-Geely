package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Robolectric because `record` stamps the line with the real process id: under plain JUnit the platform
 * call throws "not mocked" and the two tests that write a file would never reach the redaction they are
 * there to check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiagCrashHandlerTest {
    @Test fun theStackIsWrittenWithTheFailureThatCausedIt() {
        val error = IllegalStateException("no endpoint pair")
        error.stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.IphoneUsbHost", "open", "IphoneUsbHost.kt", 318))
        val lines = DiagCrashHandler.format(Thread.currentThread(), error, 5310, "09:39:52.044")
        assertTrue(lines.first().contains("FATAL"))
        assertTrue(lines.first().contains("java.lang.IllegalStateException"))
        assertTrue(lines.first().contains("09:39:52.044"))
        assertTrue(lines.first().contains("pid=5310"))
        assertTrue(lines.any { it.contains("IphoneUsbHost.kt") })
    }

    @Test fun aStackWrittenNeitherToDiskNorDeleted() {
        val folder = Files.createTempDirectory("diplay-crash").toFile()
        try {
            val file = folder.resolve("diplay.log")
            DiagCrashHandler.setLogFileForTest(file)
            val error = RuntimeException("decoder configure failed")
            error.stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.AndroidMediaSink", "configure", "AndroidMediaSink.kt", 340))
            DiagCrashHandler.recordForTest(Thread.currentThread(), error)
            val written = file.readText()
            assertTrue(written.contains("FATAL"))
            assertTrue(written.contains("decoder configure failed"))
            assertTrue(written.contains("AndroidMediaSink.kt"))
        } finally {
            DiagCrashHandler.setLogFileForTest(null)
            folder.deleteRecursively()
        }
    }

    @Test fun aCredentialOnTheStackTraceNeverReachesTheFile() {
        val folder = Files.createTempDirectory("diplay-crash-redact").toFile()
        try {
            val file = folder.resolve("diplay.log")
            DiagCrashHandler.setLogFileForTest(file)
            val error = RuntimeException("pair record=deadbeef")
            error.stackTrace = emptyArray()
            DiagCrashHandler.recordForTest(Thread.currentThread(), error)
            assertTrue(!file.readText().contains("deadbeef"))
        } finally {
            DiagCrashHandler.setLogFileForTest(null)
            folder.deleteRecursively()
        }
    }

    /**
     * A crash with no record of itself is worse than no handler at all: the process dies anyway, and
     * the default handler never runs, so the platform's own "app has stopped" path loses its cause too.
     */
    @Test fun theOriginalHandlerStillRunsWhenRecordingFails() {
        val seen = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, error -> seen.add(error) }
        val crash = IllegalStateException("boom")
        DiagCrashHandler.handler(previous) { _, _ -> throw RuntimeException("the disk is full") }
            .uncaughtException(Thread.currentThread(), crash)
        assertEquals(listOf<Throwable>(crash), seen)
    }

    /**
     * The first crash of a fresh install is the one most worth having, and it is exactly the one that
     * happens before any session log exists: `SessionLogFile` only creates `filesDir/logs` in `reset()`,
     * which needs a successful bootstrap, and `append` never does. Without this the crash is written
     * nowhere at all — the handler runs, the disk is never touched, and the report says nothing.
     */
    @Test fun aCrashBeforeAnySessionRanIsStillWritten() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "logs")
        directory.deleteRecursively()
        assertTrue("fixture must start with no log directory", !directory.exists())

        DiagCrashHandler.install(context)
        val error = IllegalStateException("crashed on the connection page")
        error.stackTrace = emptyArray()
        DiagCrashHandler.recordForTest(Thread.currentThread(), error)

        val written = File(directory, "diplay.log")
        assertTrue("the crash must reach disk even with no session log to append to", written.isFile)
        assertTrue(written.readText().contains("crashed on the connection page"))
    }
}
