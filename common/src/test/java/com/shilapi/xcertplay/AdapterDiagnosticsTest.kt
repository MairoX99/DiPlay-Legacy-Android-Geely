package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterDiagnosticsTest {
    /** The page's lines reach the run in the order they happened, and are not left for the run after it. */
    @Test fun whatThePageRecordedIsHandedOverOnce() {
        AdapterDiagnostics.drain()
        AdapterDiagnostics.record("adapter-bt: BROADCASTING (advertising)")
        AdapterDiagnostics.record("adapter-bt: PAIRING (io-capability-request)")
        AdapterDiagnostics.record("adapter-bt: acl handle=0xb peer=[address]")

        assertEquals(
            listOf(
                "adapter-bt: BROADCASTING (advertising)",
                "adapter-bt: PAIRING (io-capability-request)",
                "adapter-bt: acl handle=0xb peer=[address]",
            ),
            AdapterDiagnostics.drain(),
        )
        assertTrue(AdapterDiagnostics.drain().isEmpty())
    }

    /**
     * A page left open on the adapter records every bring-up it goes through. Only the attempt in front of the
     * run being started is of interest, and the oldest lines are the ones to lose.
     */
    @Test fun onlyTheMostRecentLinesAreKept() {
        AdapterDiagnostics.drain()
        repeat(74) { AdapterDiagnostics.record("line $it") }

        val held = AdapterDiagnostics.drain()

        assertEquals(64, held.size)
        assertEquals("line 10", held.first())
        assertEquals("line 73", held.last())
    }
}
