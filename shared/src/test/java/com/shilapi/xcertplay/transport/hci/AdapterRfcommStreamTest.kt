package com.shilapi.xcertplay.transport.hci

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterRfcommStreamTest {
    private class FakeDlc : RfcommDlc {
        val sent = CopyOnWriteArrayList<ByteArray>()
        var closes = 0
        var failSend: IOException? = null
        var failClose: IOException? = null
        var holds = 0
        var releases = 0

        override fun send(handle: Int, dlci: Int, payload: ByteArray) {
            failSend?.let { throw it }
            sent += payload
        }

        override fun close(handle: Int, dlci: Int) {
            failClose?.let { throw it }
            closes += 1
        }

        override fun holdCredits() {
            holds += 1
        }

        override fun releaseCredits() {
            releases += 1
        }
    }

    private fun stream(dlc: FakeDlc = FakeDlc()) = AdapterRfcommStream(dlc, HANDLE, DLCI)

    /**
     * The pusher is the adapter's HCI reader, and it is the only thread that can read an ACL permit back.
     * A consumer that stopped draining must not make it wait — the phone is held back through its credits
     * instead, which is what the channel is told to do.
     */
    @Test(timeout = 15_000)
    fun aConsumerThatStopsDrainingHoldsThePhonesCreditsWithoutStallingThePusher() {
        val dlc = FakeDlc()
        val stream = stream(dlc)

        // Past the point where the stream asks for the phone to be held, and short of the bound above it.
        repeat(50) { stream.onData(ByteArray(1_024)) }

        // Reaching here at all is half the assertion: a pusher made to wait would never return.
        assertEquals(1, dlc.holds)
        assertEquals(0, dlc.releases)
    }

    @Test fun recvReturnsNullWhenNothingArrivesBeforeTheTimeout() {
        assertNull(stream().recv(maxBytes = 16, timeoutMillis = 50))
    }

    @Test fun recvReturnsWhatTheChannelPushed() {
        val stream = stream()
        stream.onData(byteArrayOf(0x01, 0x02, 0x03))

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), stream.recv(maxBytes = 16, timeoutMillis = 100))
    }

    @Test fun recvGivesAtMostMaxBytesAndKeepsTheRemainderInOrder() {
        val stream = stream()
        stream.onData(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))

        assertArrayEquals(byteArrayOf(0x01, 0x02), stream.recv(maxBytes = 2, timeoutMillis = 100))
        assertArrayEquals(byteArrayOf(0x03, 0x04), stream.recv(maxBytes = 2, timeoutMillis = 100))
        assertArrayEquals(byteArrayOf(0x05), stream.recv(maxBytes = 2, timeoutMillis = 100))
    }

    @Test fun recvReturnsAnEmptyArrayAfterThePeerEnded() {
        val stream = stream()
        stream.onPeerEnded()

        assertArrayEquals(ByteArray(0), stream.recv(maxBytes = 16, timeoutMillis = 100))
    }

    @Test fun recvStillDrainsWhatArrivedBeforeThePeerEnded() {
        val stream = stream()
        stream.onData(byteArrayOf(0x07))
        stream.onPeerEnded()

        assertArrayEquals(byteArrayOf(0x07), stream.recv(maxBytes = 16, timeoutMillis = 100))
        assertArrayEquals(ByteArray(0), stream.recv(maxBytes = 16, timeoutMillis = 100))
    }

    @Test fun sendGoesOutOnTheChannel() {
        val dlc = FakeDlc()
        stream(dlc).send(byteArrayOf(0xAA.toByte()))

        assertEquals(1, dlc.sent.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), dlc.sent[0])
    }

    @Test fun sendAfterCloseFailsInsteadOfDropping() {
        val stream = stream()
        stream.close()

        val failure = runCatching { stream.send(byteArrayOf(0x01)) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("closed"))
    }

    @Test fun closeIsIdempotentAndClosesTheChannelOnlyOnce() {
        val dlc = FakeDlc()
        val stream = stream(dlc)

        stream.close()
        stream.close()

        assertEquals(1, dlc.closes)
    }

    @Test fun closeUnblocksAWaitingRecv() {
        val stream = stream()
        val waiter = Thread { stream.recv(maxBytes = 16, timeoutMillis = 5_000) }
        waiter.isDaemon = true
        waiter.start()
        Thread.sleep(50)

        stream.close()
        waiter.join(2_000)

        assertFalse(waiter.isAlive)
    }

    @Test fun recvRejectsArgumentsThatCannotWork() {
        val stream = stream()
        assertTrue(runCatching { stream.recv(maxBytes = 0, timeoutMillis = 10) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { stream.recv(maxBytes = 1, timeoutMillis = -1) }.exceptionOrNull() is IllegalArgumentException)
    }

    /** Holding the credits is what stops the phone overrunning the buffer; draining gives them back. */
    @Test(timeout = 15_000)
    fun drainingReleasesTheCreditsTheStreamHeldAndLosesNothing() {
        val dlc = FakeDlc()
        val stream = stream(dlc)
        val payload = ByteArray(50 * 1_024) { (it % 251).toByte() }
        payload.asList().chunked(1_024).forEach { stream.onData(it.toByteArray()) }
        assertEquals(1, dlc.holds)

        val received = ByteArrayOutputStream()
        while (received.size() < payload.size) {
            val chunk = stream.recv(maxBytes = 8_192, timeoutMillis = 2_000) ?: break
            if (chunk.isEmpty()) break
            received.write(chunk)
        }

        assertArrayEquals(payload, received.toByteArray())
        assertEquals(1, dlc.releases)
    }

    /**
     * A phone that ignores its credits is the one way the bound can be reached. No byte can be dropped without
     * corrupting the iAP2 stream, so the stream fails and says so rather than losing one quietly.
     */
    @Test fun bytesSentPastTheCreditsFailTheStreamInsteadOfBeingDropped() {
        val stream = stream()
        repeat(65) { stream.onData(ByteArray(1_024)) }

        val failure = runCatching {
            while (true) {
                val chunk = stream.recv(maxBytes = 8_192, timeoutMillis = 100)
                if (chunk == null || chunk.isEmpty()) break
            }
        }.exceptionOrNull()

        assertTrue("expected the stream to fail", failure is IOException)
    }

    /** A failed write leaves the stream failed, exactly as it does on the car's own stack. */
    @Test fun aFailedSendLeavesTheStreamFailedForLaterCalls() {
        val dlc = FakeDlc()
        dlc.failSend = IOException("the channel is gone")
        val stream = stream(dlc)

        val sent = runCatching { stream.send(byteArrayOf(0x01)) }.exceptionOrNull()
        val afterwards = runCatching { stream.recv(maxBytes = 8, timeoutMillis = 50) }.exceptionOrNull()

        assertTrue(sent is IOException)
        assertSame(sent, afterwards)
    }

    /** The owner is the last one who can report that the channel did not go down, so close does not hide it. */
    @Test fun closeRaisesAFailureTheChannelReported() {
        val dlc = FakeDlc()
        dlc.failClose = IOException("the channel did not close")
        val stream = stream(dlc)

        val failure = runCatching { stream.close() }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    private companion object {
        const val HANDLE = 0x0B
        const val DLCI = 2
    }
}
