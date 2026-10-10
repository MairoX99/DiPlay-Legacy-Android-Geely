package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DiagSinkTest {
    @Before fun clear() = DiagSink.reset()

    @Test fun linesBeforeAWriterArrivesAreHeldNotDropped() {
        DiagSink.line("usb/discover: searching")
        DiagSink.line("usb/wait: device not present")
        val written = mutableListOf<String>()
        DiagSink.attach { written.add(it) }
        assertEquals(listOf("usb/discover: searching", "usb/wait: device not present"), written)
    }

    @Test fun linesAfterAttachGoStraightToTheWriter() {
        val written = mutableListOf<String>()
        DiagSink.attach { written.add(it) }
        DiagSink.line("after")
        assertEquals(listOf("after"), written)
    }

    @Test fun heldLinesAreFlushedOnlyOnce() {
        DiagSink.line("held")
        val first = mutableListOf<String>()
        DiagSink.attach { first.add(it) }
        val second = mutableListOf<String>()
        DiagSink.attach { second.add(it) }
        assertEquals(listOf("held"), first)
        assertEquals(emptyList<String>(), second)
    }

    @Test fun detachStopsWritingAndHoldsTheNextLinesAgain() {
        val written = mutableListOf<String>()
        DiagSink.attach { written.add(it) }
        DiagSink.detach()
        DiagSink.line("during teardown")
        assertEquals(emptyList<String>(), written)
        val after = mutableListOf<String>()
        DiagSink.attach { after.add(it) }
        assertEquals(listOf("during teardown"), after)
    }

    @Test fun aLongRunBeforeAnyWriterKeepsTheNewestAndSaysHowManyItLost() {
        repeat(DiagSink.PENDING_CAPACITY + 40) { DiagSink.line("line $it") }
        val written = mutableListOf<String>()
        DiagSink.attach { written.add(it) }
        assertEquals(DiagSink.PENDING_CAPACITY, written.size)
        assertEquals("line 40", written.first())
        assertEquals("line ${DiagSink.PENDING_CAPACITY + 39}", written.last())
        assertEquals(40L, DiagSink.overflowedLines())
    }

    @Test fun attachedStateIsObservableSoTheWiringCanBeChecked() {
        assertEquals(false, DiagSink.isAttached())
        DiagSink.attach { }
        assertEquals(true, DiagSink.isAttached())
        DiagSink.detach()
        assertEquals(false, DiagSink.isAttached())
    }

    @Test fun concurrentTransportThreadsLoseNothingAndDuplicateNothing() {
        val written = java.util.Collections.synchronizedList(mutableListOf<String>())
        DiagSink.attach { written.add(it) }
        val workers = (0 until 8).map { worker ->
            Thread { repeat(200) { index -> DiagSink.line("worker $worker line $index") } }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        assertEquals(1600, written.size)
        assertEquals(1600, written.toSet().size)
    }
}
