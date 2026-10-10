package com.shilapi.xcertplay

import java.util.concurrent.atomic.AtomicLong

/**
 * What the log lost, so the report can say so.
 *
 * Every one of these paths used to fail silently: a full disk, a full queue and a closed writer all
 * produced the same result as a quiet run. A report that cannot tell "nothing happened" from "the
 * evidence was dropped" is what makes a missing line unreadable.
 */
object DiagnosticCounters {
    private val logWriteFailures = AtomicLong()
    private val logQueueDrops = AtomicLong()

    fun noteLogWriteFailure() { logWriteFailures.incrementAndGet() }
    fun noteLogQueueDrop() { logQueueDrops.incrementAndGet() }

    fun logWriteFailures(): Long = logWriteFailures.get()
    fun logQueueDrops(): Long = logQueueDrops.get()

    fun summary(): String =
        "Log losses: queueDrops=${logQueueDrops()} writeFailures=${logWriteFailures()} " +
            "sinkOverflows=${com.shilapi.xcertplay.DiagSink.overflowedLines()}"

    fun reset() {
        logWriteFailures.set(0L)
        logQueueDrops.set(0L)
    }
}
