package com.shilapi.xcertplay.transport.hci

import java.io.Closeable

/**
 * The one seam over the adapter. Everything above it is plain Kotlin so the protocol layers can be
 * driven by a fake in tests, and everything about Android's USB API stays below it.
 */
interface HciTransport : Closeable {
    fun sendCommand(command: ByteArray)

    /** Blocks for at most [timeoutMillis]; returns one whole HCI event packet, or null on timeout. */
    fun readEvent(timeoutMillis: Long): ByteArray?

    fun writeAcl(packet: ByteArray)

    /** Blocks for at most [timeoutMillis]; returns raw ACL bytes (possibly several packets), or null. */
    fun readAcl(timeoutMillis: Long): ByteArray?
}
