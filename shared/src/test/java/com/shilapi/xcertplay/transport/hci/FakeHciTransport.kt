package com.shilapi.xcertplay.transport.hci

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An [HciTransport] the tests drive by hand.
 *
 * `readEvent`/`readAcl` block until something is queued or the timeout elapses, so the controller's reader
 * thread behaves against it exactly as it does against USB. Not production code.
 */
internal class FakeHciTransport : HciTransport {
    private val events = LinkedBlockingQueue<ByteArray>()
    private val aclReads = LinkedBlockingQueue<ByteArray>()
    private val queuedOnCommand = LinkedBlockingQueue<ByteArray>()

    val commands = CopyOnWriteArrayList<ByteArray>()
    val aclWrites = CopyOnWriteArrayList<ByteArray>()

    @Volatile var closed = false
        private set

    /** Called with every command the stack sends, so a test can play the phone's side. */
    var onCommand: ((ByteArray) -> Unit)? = null

    /** Called with every ACL packet the stack writes. */
    var onAclWrite: ((ByteArray) -> Unit)? = null

    /** Makes [event] available to the reader as soon as the next command is sent. */
    fun enqueueOnCommand(event: ByteArray) {
        queuedOnCommand += event
    }

    fun enqueueEvent(event: ByteArray) {
        events += event
    }

    fun enqueueAcl(bytes: ByteArray) {
        aclReads += bytes
    }

    override fun sendCommand(command: ByteArray) {
        commands += command
        onCommand?.invoke(command)
        while (true) {
            val pending = queuedOnCommand.poll() ?: return
            events += pending
        }
    }

    override fun writeAcl(packet: ByteArray) {
        aclWrites += packet
        onAclWrite?.invoke(packet)
    }

    override fun readEvent(timeoutMillis: Long): ByteArray? =
        events.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    override fun readAcl(timeoutMillis: Long): ByteArray? =
        aclReads.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    override fun close() {
        closed = true
    }
}
