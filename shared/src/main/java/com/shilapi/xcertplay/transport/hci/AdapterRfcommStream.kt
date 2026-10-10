package com.shilapi.xcertplay.transport.hci

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.ArrayDeque

/**
 * A USB-adapter RFCOMM channel as a [BlockingDuplexByteStream], with the same semantics as
 * [com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream]: bounded buffering, a null result meaning only
 * that the timeout elapsed, an empty array once the peer has ended its output, an idempotent close, and a
 * failure that stays failed.
 *
 * Backpressure is the one place the two cannot work the same way. A socket has a reader of its own, so its
 * stream blocks that reader until the consumer drains. Here the pusher is the adapter's single HCI reader,
 * which is also the only thread that can read back an ACL permit — making it wait would leave the sender
 * waiting for itself. A full buffer is therefore answered at the far end instead: the channel withholds the
 * phone's RFCOMM credits ([RfcommDlc.holdCredits]) and the phone stops on its own. Nothing is dropped, which
 * is what iAP2 needs — it is a framed protocol, and a byte lost here surfaces much later as a parse error.
 */
internal class AdapterRfcommStream(
    private val dlc: RfcommDlc,
    private val handle: Int,
    private val dlci: Int,
    private val onDiagnostic: (String) -> Unit = {},
) : BlockingDuplexByteStream {
    private val lock = Object()
    private val sendLock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var peerEnded = false
    private var closed = false
    private var failure: IOException? = null

    /** True while the channel has been asked to stop granting the phone room. */
    private var creditsHeld = false

    /**
     * The RFCOMM channel routes inbound user data here.
     *
     * Runs on the HCI reader, which must never be made to wait, so this never blocks — the phone is held
     * back through its credits rather than by stalling the thread that carries them.
     */
    fun onData(data: ByteArray) {
        if (data.isEmpty()) return
        var holdNow = false
        var queued = 0
        var overflowed = false
        synchronized(lock) {
            if (closed || failure != null) return
            if (pendingBytes + data.size > MAX_PENDING_BYTES) {
                // The phone sent past the credits it was granted: there is no room, and no way to make room
                // that does not lose bytes. Failing the stream is the only honest answer; a session fed by a
                // stream that quietly dropped a byte here would fail somewhere else entirely, and later.
                failure = IOException(
                    "the iPhone sent ${data.size} bytes while its RFCOMM credits were exhausted",
                )
                overflowed = true
                lock.notifyAll()
            } else {
                pending.addLast(data.copyOf())
                pendingBytes += data.size
                if (!creditsHeld && pendingBytes >= HOLD_CREDITS_AT) {
                    creditsHeld = true
                    holdNow = true
                    queued = pendingBytes
                }
                lock.notifyAll()
            }
        }
        if (holdNow) {
            try {
                dlc.holdCredits()
            } catch (_: Exception) {
                // A channel that cannot take the hint still must not break the reader.
            }
            report("adapter-bt: $queued bytes queued; withholding RFCOMM credits")
        }
        if (overflowed) report("adapter-bt: stream buffer overrun; failing the stream")
    }

    /** The RFCOMM channel routes a peer-initiated close here. */
    fun onPeerEnded() {
        synchronized(lock) {
            peerEnded = true
            lock.notifyAll()
        }
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) throw IOException("Adapter RFCOMM stream is closed")
            }
            try {
                dlc.send(handle, dlci, data)
            } catch (error: Exception) {
                val io = error as? IOException
                    ?: IOException("Could not send on the RFCOMM channel", error)
                throw fail(io)
            }
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        while (true) {
            var release = false
            val chunk = synchronized(lock) {
                val available = takePendingLocked(maxBytes)
                if (available != null) {
                    if (creditsHeld && pendingBytes <= RELEASE_CREDITS_AT) {
                        creditsHeld = false
                        release = true
                    }
                    available
                } else {
                    // Buffered bytes go out before a failure is raised, exactly as on the car's own stack: a
                    // stream that failed is still the only place its remaining bytes live.
                    failure?.let { throw it }
                    if (peerEnded || closed) return EMPTY

                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0) return null
                    try {
                        lock.wait(
                            remainingNanos / NANOS_PER_MILLISECOND,
                            (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                        )
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    null
                }
            }
            if (chunk != null) {
                if (release) releaseCredits()
                return chunk
            }
        }
    }

    /**
     * Idempotent, and closes the channel this stream owns, which is all it owns.
     *
     * A close that fails is raised, as [com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream] raises
     * it: the owner is the only one left who can report that the channel did not go down.
     */
    override fun close() {
        val firstClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                lock.notifyAll()
                true
            }
        }
        if (!firstClose) return
        dlc.close(handle, dlci)
    }

    private fun releaseCredits() {
        try {
            dlc.releaseCredits()
        } catch (error: Exception) {
            report("adapter-bt: could not release RFCOMM credits: ${error.javaClass.simpleName}")
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) {
            lock.notifyAll()
            return chunk
        }

        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        lock.notifyAll()
        return head
    }

    private fun fail(io: IOException): IOException {
        synchronized(lock) {
            failure?.let { return it }
            // Closing the channel on purpose unblocks a caller mid-write; that is cancellation, not a new
            // transport failure.
            if (closed) return IOException("Adapter RFCOMM stream is closed")
            failure = io
            lock.notifyAll()
        }
        report("adapter-bt: stream failed failureClass=${io.javaClass.simpleName}")
        return io
    }

    private fun report(message: String) {
        try {
            onDiagnostic(message)
        } catch (_: Exception) {
            // A diagnostic callback cannot be allowed to break the stream.
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private companion object {
        /** The same bound the car's own stream holds, so neither path buffers more than the other. */
        const val MAX_PENDING_BYTES = 65_536

        /** Hold the phone's credits once this much is queued. The slack left to the bound above is what the
         *  phone may still have in flight when it is held — well under it, since it holds at most ten frames. */
        const val HOLD_CREDITS_AT = 49_152

        /** Grant again once the consumer has drained back to here. */
        const val RELEASE_CREDITS_AT = 16_384
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val EMPTY = ByteArray(0)
    }
}
