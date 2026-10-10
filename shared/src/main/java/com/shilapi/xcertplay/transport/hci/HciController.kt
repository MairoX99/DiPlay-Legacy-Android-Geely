package com.shilapi.xcertplay.transport.hci

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal sealed class HciCommandResult {
    data class Complete(val returnParameters: ByteArray) : HciCommandResult()
    data class Status(val status: Int) : HciCommandResult()
    data class Failed(val status: Int) : HciCommandResult()
    object TimedOut : HciCommandResult()
}

/**
 * Owns the adapter's command/event traffic.
 *
 * The reader thread is the only caller of [HciTransport.readEvent] and [HciTransport.readAcl]; a
 * command's caller waits on a queue that the reader fills. Every wait is bounded, so a yanked
 * adapter surfaces as a timeout or an error instead of a thread parked forever.
 *
 * ACL writes go out on their own thread, because the adapter's window is released by an event only the reader
 * can read: a writer that waited for a permit while running on the reader would be waiting for itself.
 */
internal class HciController(
    private val transport: HciTransport,
    private val aclSink: (HciAclData) -> Unit,
    private val eventSink: (HciEventPacket) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
    private val aclPermitTimeoutMillis: Long = ACL_PERMIT_TIMEOUT_MILLIS,
) : Closeable {
    @Volatile var aclPayloadBytes: Int = DEFAULT_ACL_PAYLOAD_BYTES
        private set

    private val closed = AtomicBoolean(false)

    /** Set once the reader has stopped for a reason other than [close]: nothing will ever release the window. */
    @Volatile private var readerStopped = false

    /**
     * Set once a full window has gone unacknowledged past [aclPermitTimeoutMillis]. The reader can still be alive
     * and blocked in a read, so [readerStopped] stays false; what makes the window unreleasable is the adapter
     * having stopped acknowledging, and writers must fail rather than queue up behind it.
     */
    @Volatile private var aclWindowStalled = false

    private val aclPermits = Object()
    private var outstandingAclPackets = 0

    /**
     * How many ACL packets the adapter will hold at once, from its own Read_Buffer_Size.
     *
     * Defaults until the adapter says: the host must not have more packets outstanding than the controller
     * has buffers, so the number that counts is the one it reported, not a constant of ours.
     */
    @Volatile private var aclWindow = DEFAULT_ACL_WINDOW_PACKETS

    /**
     * One queue per command opcode, so a reply goes to the caller it belongs to.
     *
     * With a single queue every waiter has to throw away whatever is not its own, and a thrown-away reply is
     * gone for good: two commands in flight at once became two timeouts. Only a handful of opcodes are ever
     * in use, so the queues are bounded by the command set rather than by traffic.
     */
    private val responses = ConcurrentHashMap<Int, LinkedBlockingQueue<HciEventPacket>>()
    private val reassembler = HciAclReassembler { dropped ->
        onDiagnostic("adapter-bt: dropped $dropped unparsable ACL bytes and resynchronised")
    }
    private val writes = LinkedBlockingQueue<AclWrite>()
    private val reader = Thread(::readLoop, "adapter-bt-reader").apply { isDaemon = true }
    private val sender = Thread(::sendLoop, "adapter-bt-sender").apply { isDaemon = true }

    private class AclWrite(val handle: Int, val packetBoundary: Int, val payload: ByteArray) {
        private val done = java.util.concurrent.CountDownLatch(1)
        private val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()

        fun complete(failure: Throwable?) {
            error.set(failure)
            done.countDown()
        }

        /** Waits for the write to happen; a null result means it did. */
        fun await(): Throwable? {
            while (true) {
                try {
                    done.await()
                    return error.get()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return IOException("interrupted while writing an ACL packet")
                }
            }
        }
    }

    init {
        // The sender can run before start(): it only drains an empty queue until something is written, and a
        // write that waits for the reader to be started would be a wait nobody asked for.
        sender.start()
    }

    fun start() {
        reader.start()
    }

    fun command(
        packet: ByteArray,
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
    ): HciCommandResult {
        val opcode = opcodeOf(packet)
        if (closed.get()) return HciCommandResult.Failed(STATUS_CLOSED)
        if (readerStopped) return HciCommandResult.Failed(STATUS_READER_STOPPED)
        return try {
            transport.sendCommand(packet)
            awaitResponse(opcode, timeoutMillis)
        } catch (error: IOException) {
            onDiagnostic("adapter-bt: command 0x${opcode.toString(16)} failed: ${error.javaClass.simpleName}")
            HciCommandResult.Failed(STATUS_IO_ERROR)
        }
    }

    /**
     * Records the adapter's buffer: the ACL MTU, and how many packets it will hold at once.
     *
     * [aclPayloadBytes] is the part of the MTU an L2CAP PDU can use. The packet count is what bounds how many
     * ACL writes may be in flight — reading it and then working from a constant of our own would overrun a
     * radio that reports fewer buffers than that constant.
     */
    fun applyBufferSize(aclMtu: Int, maxPackets: Int) {
        aclPayloadBytes = (aclMtu - L2CAP_HEADER_BYTES).coerceAtLeast(MINIMUM_ACL_PAYLOAD_BYTES)
        if (maxPackets > 0) aclWindow = maxPackets
    }

    /**
     * Sends a command and returns without waiting for its completion.
     *
     * Event handlers run on the reader thread, so a handler that replies (an SSP negotiation does) must not
     * wait for the completion the same reader would have to deliver.
     */
    fun send(packet: ByteArray) {
        if (closed.get()) throw IOException("HCI controller is closed")
        transport.sendCommand(packet)
    }

    /**
     * Writes one ACL packet, waiting first for the adapter's small buffer to have room. The window is filled by
     * Number_Of_Completed_Packets, which the reader thread releases.
     *
     * Called on the reader thread this hands the packet to the sender and returns: waiting there would wait for
     * an event the same thread has to read.
     */
    fun sendAcl(handle: Int, packetBoundary: Int, payload: ByteArray) {
        if (closed.get()) throw IOException("HCI controller is closed")
        val write = AclWrite(handle, packetBoundary, payload)
        writes += write
        if (Thread.currentThread() === reader) return
        write.await()?.let { throw it }
    }

    private fun awaitResponse(opcode: Int, timeoutMillis: Long): HciCommandResult {
        val queue = queueFor(opcode)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return HciCommandResult.TimedOut
            val event = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return HciCommandResult.TimedOut
            // Only this opcode's replies are ever on this queue, so there is nothing to filter out and
            // nothing of another caller's to drop.
            when (event.code) {
                HciEvents.COMMAND_COMPLETE -> {
                    val status = HciEvents.commandCompleteStatus(event)
                    return if (status == 0) {
                        HciCommandResult.Complete(HciEvents.commandCompleteReturnParameters(event))
                    } else {
                        HciCommandResult.Failed(status)
                    }
                }
                HciEvents.COMMAND_STATUS -> {
                    val status = HciEvents.commandStatus(event).second
                    // A command answered with Command_Status is acknowledged and done here; anything it
                    // produces later arrives as its own event (Connection_Complete and friends) through the sink.
                    return if (status == 0) {
                        HciCommandResult.Status(status)
                    } else {
                        HciCommandResult.Failed(status)
                    }
                }
                else -> continue
            }
        }
    }

    private fun queueFor(opcode: Int): LinkedBlockingQueue<HciEventPacket> =
        responses.computeIfAbsent(opcode) { LinkedBlockingQueue() }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                try {
                    readEvents()
                    readAcl()
                } catch (error: IOException) {
                    throw error
                } catch (error: Exception) {
                    // A malformed packet costs that packet; the link stays up.
                    onDiagnostic("adapter-bt: dropped a bad read: ${error.javaClass.simpleName}")
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                onDiagnostic("adapter-bt: reader stopped: ${error.javaClass.simpleName}")
            }
        } finally {
            readerStopped = true
            synchronized(aclPermits) { aclPermits.notifyAll() }
        }
    }

    /**
     * Writes queued ACL packets, waiting for the adapter's window here rather than on whoever asked for the
     * write.
     */
    private fun sendLoop() {
        while (true) {
            val write = try {
                writes.poll(SENDER_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                failQueuedWrites()
                return
            }
            if (write == null) {
                if (closed.get()) return
                continue
            }
            if (closed.get()) {
                write.complete(IOException("HCI controller is closed"))
                continue
            }
            try {
                awaitAclPermit()
                writePacket(write)
                write.complete(null)
            } catch (error: Throwable) {
                write.complete(error)
                reportFailedWrite(write, error)
            }
        }
    }

    private fun writePacket(write: AclWrite) {
        val header = (write.handle and 0x0FFF) or ((write.packetBoundary and 0x03) shl 12)
        synchronized(aclPermits) { outstandingAclPackets += 1 }
        try {
            transport.writeAcl(
                byteArrayOf(
                    (header and 0xFF).toByte(),
                    ((header shr 8) and 0xFF).toByte(),
                    (write.payload.size and 0xFF).toByte(),
                    ((write.payload.size shr 8) and 0xFF).toByte(),
                ) + write.payload,
            )
        } catch (error: Throwable) {
            releaseAclPackets(1)
            throw error
        }
    }

    /**
     * Names a packet the radio never took.
     *
     * [sendAcl] hands the write to this thread and returns when its caller is the reader, which is the case for
     * every reply an inbound packet provokes — an L2CAP answer, an SDP record, an RFCOMM response. That caller
     * cannot wait for the window the same thread has to release, so without this line the outcome of those
     * writes goes nowhere: a phone that asked and got no answer reads exactly like a phone that never asked, and
     * the one run that could say which is the one that was lost.
     */
    private fun reportFailedWrite(write: AclWrite, error: Throwable) {
        val detail = error.message?.let { "$it" } ?: error.javaClass.simpleName
        onDiagnostic(
            "adapter-bt: ACL write failed handle=0x${write.handle.toString(16)} " +
                "boundary=${write.packetBoundary} bytes=${write.payload.size}: $detail",
        )
    }

    private fun readEvents() {
        val bytes = transport.readEvent(EVENT_POLL_MILLIS) ?: return
        var offset = 0
        // A read can carry more than one event. Parsing only the head of the buffer cost every event behind
        // it, and during pairing the one behind it can be the IO_Capability_Request.
        while (offset < bytes.size) {
            val event = HciPackets.event(bytes, offset) ?: break
            offset += HciPackets.eventBytes(event)
            dispatchEvent(event)
        }
        if (offset < bytes.size) {
            onDiagnostic("adapter-bt: dropped ${bytes.size - offset} trailing event bytes")
        }
    }

    private fun dispatchEvent(event: HciEventPacket) {
        when (event.code) {
            HciEvents.NUMBER_OF_COMPLETED_PACKETS ->
                releaseAclPackets(HciEvents.numberOfCompletedPackets(event).sumOf { it.second })
            // Num_HCI_Command_Packets(1) + Command_Opcode(2) + Status(1); anything shorter has no opcode to
            // route it by, and dropping it here is what keeps a malformed event from killing the reader.
            HciEvents.COMMAND_COMPLETE, HciEvents.COMMAND_STATUS ->
                if (event.parameters.size < COMMAND_REPLY_PARAMETER_BYTES) {
                    onDiagnostic("adapter-bt: dropped a ${event.parameters.size}-octet command reply")
                } else {
                    queueFor(replyOpcodeOf(event)).offer(event)
                }
            else -> eventSink(event)
        }
    }

    private fun replyOpcodeOf(event: HciEventPacket): Int =
        if (event.code == HciEvents.COMMAND_COMPLETE) {
            HciEvents.commandCompleteOpcode(event)
        } else {
            HciEvents.commandStatus(event).first
        }

    private fun readAcl() {
        val bytes = transport.readAcl(ACL_POLL_MILLIS) ?: return
        reassembler.feed(bytes).forEach(aclSink)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { transport.close() }
        synchronized(aclPermits) { aclPermits.notifyAll() }
        reader.interrupt()
        sender.interrupt()
        failQueuedWrites()
    }

    private fun failQueuedWrites() {
        while (true) {
            val write = writes.poll() ?: return
            write.complete(IOException("HCI controller is closed"))
        }
    }

    private fun awaitAclPermit() {
        val deadline = System.currentTimeMillis() + aclPermitTimeoutMillis
        synchronized(aclPermits) {
            while (!closed.get() && !readerStopped && !aclWindowStalled &&
                outstandingAclPackets >= aclWindow
            ) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    // The reader is alive but the adapter has stopped acknowledging what it read, so nothing will
                    // ever release this window. Deciding that here is what stops every caller queued behind this
                    // one from parking on it too, with the whole wireless run stopped and nothing saying why.
                    aclWindowStalled = true
                    onDiagnostic("adapter-bt: the adapter stopped acknowledging ACL packets")
                    aclPermits.notifyAll()
                    break
                }
                try {
                    aclPermits.wait(minOf(ACL_PERMIT_WAIT_MILLIS, remaining))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted while waiting for ACL buffer space")
                }
            }
        }
        if (closed.get()) throw IOException("HCI controller is closed")
        if (aclWindowStalled) throw IOException("the adapter stopped acknowledging ACL packets")
        // Nothing will release the window once the reader has stopped, so waiting would be forever.
        if (readerStopped) throw IOException("the adapter stopped answering")
    }

    private fun releaseAclPackets(count: Int) {
        synchronized(aclPermits) {
            outstandingAclPackets = (outstandingAclPackets - count).coerceAtLeast(0)
            aclPermits.notifyAll()
        }
    }

    private companion object {
        const val EVENT_POLL_MILLIS = 200L
        const val ACL_POLL_MILLIS = 50L
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 4_000L
        const val DEFAULT_ACL_PAYLOAD_BYTES = 27
        const val L2CAP_HEADER_BYTES = 4
        const val MINIMUM_ACL_PAYLOAD_BYTES = 8

        /** An event's Num_HCI_Command_Packets + Command_Opcode + Status. */
        const val COMMAND_REPLY_PARAMETER_BYTES = 4

        /** Until the adapter reports its own count. */
        const val DEFAULT_ACL_WINDOW_PACKETS = 4
        const val ACL_PERMIT_WAIT_MILLIS = 200L

        /** How long a full window may stay unacknowledged before the adapter is called stalled. */
        const val ACL_PERMIT_TIMEOUT_MILLIS = 10_000L
        const val SENDER_POLL_MILLIS = 200L
        const val STATUS_CLOSED = 0xC0
        const val STATUS_IO_ERROR = 0xC1
        const val STATUS_READER_STOPPED = 0xC2

        fun opcodeOf(packet: ByteArray): Int =
            (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
    }
}
