package com.shilapi.xcertplay.transport.hci

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HciControllerTest {
    @Test fun commandCompletesWithItsReturnParameters() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})
        transport.enqueueOnCommand(commandComplete(0x1009, 0, HciCommands.formatAddress("F4:4E:FC:F8:8B:36")))
        controller.start()

        val result = controller.command(HciCommands.readBdAddr())

        assertTrue(result is HciCommandResult.Complete)
        assertEquals(
            "F4:4E:FC:F8:8B:36",
            HciCommands.parseAddress((result as HciCommandResult.Complete).returnParameters.copyOfRange(1, 7)),
        )
        controller.close()
    }

    @Test fun aNonZeroStatusIsFailedAndItsParametersAreNotHandedOut() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})
        transport.enqueueOnCommand(commandComplete(0x0C03, 0x01))
        controller.start()

        assertEquals(HciCommandResult.Failed(0x01), controller.command(HciCommands.reset()))
        controller.close()
    }

    @Test fun commandStatusIsReportedAsStatusNotComplete() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})
        transport.enqueueOnCommand(commandStatus(0x0405, 0x00))
        controller.start()

        assertEquals(
            HciCommandResult.Status(0x00),
            controller.command(HciCommands.createConnection("F4:4E:FC:F8:8B:36", allowRoleSwitch = 0x01)),
        )
        controller.close()
    }

    @Test fun anUnansweredCommandTimesOutInsteadOfBlockingForever() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})
        controller.start()

        assertEquals(HciCommandResult.TimedOut, controller.command(HciCommands.reset(), timeoutMillis = 150))
        controller.close()
    }

    @Test fun eventsNobodyAwaitsReachTheEventSink() {
        val transport = FakeHciTransport()
        val seen = CopyOnWriteArrayList<Int>()
        val controller = HciController(transport, {}, { seen += it.code })
        transport.enqueueOnCommand(byteArrayOf(0x31, 0x06) + HciCommands.formatAddress("CC:60:23:D7:2F:5B"))
        controller.start()

        controller.command(HciCommands.reset(), timeoutMillis = 200)
        assertTrue(seen.contains(0x31))
        controller.close()
    }

    /** A reply sent from inside an event handler must not wait for its own completion: the reader delivers it. */
    @Test fun sendDeliversACommandWithoutWaitingForItsCompletion() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})
        controller.start()

        val address = "F4:4E:FC:F8:8B:36"
        controller.send(HciCommands.ioCapabilityRequestReply(address, 0x03, 0, 0x02))

        assertArrayEquals(
            byteArrayOf(0x2B, 0x04, 0x09) + HciCommands.formatAddress(address) + byteArrayOf(0x03, 0x00, 0x02),
            transport.commands.last(),
        )
        controller.close()
    }

    @Test fun sendOnAClosedControllerFailsRatherThanDroppingTheCommand() {
        val controller = HciController(FakeHciTransport(), {}, {})
        controller.start()
        controller.close()

        val failure = runCatching { controller.send(HciCommands.reset()) }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test fun sendAclPutsThePacketBoundaryInTheHeader() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})

        controller.sendAcl(handle = 0x0B, packetBoundary = 1, payload = byteArrayOf(0xAA.toByte()))

        // handle 0x0B with packet boundary 0b01 in bits 12-13.
        assertArrayEquals(
            byteArrayOf(0x0B, 0x10, 0x01, 0x00, 0xAA.toByte()),
            transport.aclWrites.last(),
        )
    }

    /** The adapter's ACL buffer is small, so only a few packets may be in flight at once. */
    @Test fun aclWritesStopAtTheWindowAndResumeWhenPacketsComplete() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})
        controller.start()
        repeat(4) { controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x01)) }

        val blocked = Thread { controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x02)) }
        blocked.isDaemon = true
        blocked.start()
        Thread.sleep(150)
        assertTrue("the fifth packet must wait for the window", blocked.isAlive)

        transport.enqueueEvent(numberOfCompletedPackets(0x0B, 4))
        blocked.join(2_000)

        assertFalse(blocked.isAlive)
        assertEquals(5, transport.aclWrites.size)
        controller.close()
    }

    /** A malformed read must cost that read, not the link: the next command still gets its answer. */
    @Test fun aTruncatedEventDoesNotKillTheReader() {
        val reads = LinkedBlockingQueue<ByteArray>()
        val transport = object : HciTransport {
            override fun sendCommand(command: ByteArray) {
                reads += commandComplete(0x0C03, 0)
            }

            override fun readEvent(timeoutMillis: Long): ByteArray? =
                reads.poll(timeoutMillis, TimeUnit.MILLISECONDS)

            override fun readAcl(timeoutMillis: Long): ByteArray? = null

            override fun writeAcl(packet: ByteArray) = Unit

            override fun close() = Unit
        }
        val controller = HciController(transport, {}, {})
        // A Link_Key_Notification header claiming ten parameter bytes when only one arrived.
        reads += byteArrayOf(0x18, 0x0A, 0x5B)
        controller.start()

        val result = controller.command(HciCommands.reset(), timeoutMillis = 1_000)

        assertTrue(result is HciCommandResult.Complete)
        controller.close()
    }

    /**
     * A reader that has stopped must fail the writers. Its death is the one thing that stops
     * Number_Of_Completed_Packets from ever arriving, so a writer that waits for the window would wait forever.
     */
    @Test(timeout = 10_000)
    fun aDeadReaderFailsWritersInsteadOfParkingThem() {
        var dead = false
        val transport = object : HciTransport {
            override fun sendCommand(command: ByteArray) = Unit

            override fun readEvent(timeoutMillis: Long): ByteArray? {
                if (dead) throw IOException("adapter gone")
                Thread.sleep(timeoutMillis)
                return null
            }

            override fun readAcl(timeoutMillis: Long): ByteArray? = null

            override fun writeAcl(packet: ByteArray) = Unit

            override fun close() = Unit
        }
        val controller = HciController(transport, {}, {})
        controller.start()
        // Fill the adapter's window while the reader is still alive, so the next write has to wait for it.
        repeat(4) { controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x01)) }
        dead = true
        Thread.sleep(300)

        val failure = runCatching {
            controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x02))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        controller.close()
    }

    /**
     * Protocol replies are produced while reading. A reply that waited for an ACL permit would be waiting on a
     * window only that same thread can release, which is a deadlock the moment the window is full.
     */
    @Test(timeout = 10_000)
    fun aReplySentWhileReadingDoesNotWaitForTheWindow() {
        val transport = FakeHciTransport()
        val handled = CountDownLatch(1)
        var controller: HciController? = null
        controller = HciController(transport, {}, { event ->
            if (event.code == 0x31) {
                controller?.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x99.toByte()))
                handled.countDown()
            }
        })
        controller.start()
        repeat(4) { controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x01)) }
        transport.enqueueEvent(byteArrayOf(0x31, 0x06) + HciCommands.formatAddress("CC:60:23:D7:2F:5B"))

        assertTrue("the reader must not wait for a window it has to release", handled.await(2, TimeUnit.SECONDS))
        controller.close()
    }

    /**
     * A reply produced while reading is handed to the sender and its caller does not wait, so the sender is the
     * only place its failure can surface. It used to end there in silence: a packet the radio never took read
     * exactly like a phone that never asked, and those two are the one thing a failed run has to tell apart.
     */
    @Test(timeout = 10_000)
    fun aReplyWrittenWhileReadingReportsItsOwnFailure() {
        val transport = FakeHciTransport()
        val diagnostics = CopyOnWriteArrayList<String>()
        val attempted = CountDownLatch(1)
        var controller: HciController? = null
        controller = HciController(
            transport,
            {},
            { event ->
                if (event.code == 0x31) {
                    controller?.sendAcl(
                        handle = 0x0B,
                        packetBoundary = 0,
                        payload = byteArrayOf(0x99.toByte()),
                    )
                    attempted.countDown()
                }
            },
            { line -> diagnostics += line },
        )
        transport.onAclWrite = { throw IOException("USB ACL write transferred 0 of 5 bytes") }
        controller.start()
        transport.enqueueEvent(byteArrayOf(0x31, 0x06) + HciCommands.formatAddress("CC:60:23:D7:2F:5B"))

        assertTrue("the reader must have produced the reply", attempted.await(2, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline && diagnostics.none { it.contains("ACL write failed") }) {
            Thread.sleep(10)
        }

        assertTrue(
            "a write the radio refused must say so even though its caller cannot wait for it",
            diagnostics.any { it.contains("ACL write failed") && it.contains("handle=0xb") },
        )
        controller.close()
    }

    /**
     * An adapter that stays enumerated but stops acknowledging ACL packets is not a dead reader: the reader lives
     * on, so nothing marks the window unreleasable, and the sender parks on it with every caller queued behind —
     * the whole wireless run stopped with nothing on screen saying why.
     */
    @Test(timeout = 10_000)
    fun aWindowThatNeverReopensFailsTheWriterInsteadOfParkingIt() {
        val transport = FakeHciTransport()
        val diagnostics = CopyOnWriteArrayList<String>()
        val controller = HciController(
            transport,
            {},
            {},
            { line -> diagnostics += line },
            aclPermitTimeoutMillis = 400,
        )
        controller.start()
        // Four packets fill the adapter's window, and it never sends Number_Of_Completed_Packets to reopen it.
        repeat(4) { controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x01)) }

        val failure = runCatching {
            controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x02))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(diagnostics.any { it.contains("stopped acknowledging") })
        controller.close()
    }

    @Test fun aclBytesReachTheSinkAsWholePackets() {
        val transport = FakeHciTransport()
        val latch = CountDownLatch(1)
        var handle = -1
        var payload: ByteArray = ByteArray(0)
        val controller = HciController(transport, { data ->
            handle = data.handle
            payload = data.payload
            latch.countDown()
        }, {})
        controller.start()

        transport.enqueueAcl(byteArrayOf(0x0B, 0x00, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte()))

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(0x0B, handle)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), payload)
        controller.close()
    }

    /**
     * A reply that arrives before its caller asks for it must still be there for it. With one queue every
     * waiter discarded whatever was not its own, and a discarded reply is gone — two commands in flight at
     * once became two timeouts, and nothing said so.
     */
    @Test fun anAnswerIsHeldForItsOwnCallerEvenWhileAnotherWaits() {
        val transport = FakeHciTransport()
        val controller = HciController(transport, {}, {})
        controller.start()
        // Read_BD_ADDR is answered before anyone has asked for it.
        transport.enqueueEvent(commandComplete(0x1009, 0, HciCommands.formatAddress("F4:4E:FC:F8:8B:36")))

        assertEquals(HciCommandResult.TimedOut, controller.command(HciCommands.reset(), timeoutMillis = 200))

        val address = controller.command(HciCommands.readBdAddr())
        assertTrue(
            "the address's own answer must survive another command's wait",
            address is HciCommandResult.Complete,
        )
        controller.close()
    }

    /**
     * A USB read can carry more than one event. Parsing only the head of the buffer lost everything behind
     * it — and during pairing the one behind it can be the IO_Capability_Request.
     */
    @Test fun aReadCarryingTwoEventsDeliversBoth() {
        val transport = FakeHciTransport()
        val seen = CopyOnWriteArrayList<Int>()
        val controller = HciController(transport, {}, { seen += it.code })
        val ioCapabilityRequest = byteArrayOf(0x31, 0x06) + HciCommands.formatAddress("CC:60:23:D7:2F:5B")
        transport.enqueueEvent(ioCapabilityRequest + commandComplete(0x0C03, 0))
        controller.start()

        val result = controller.command(HciCommands.reset())

        assertTrue(result is HciCommandResult.Complete)
        assertTrue("the first event in the read must reach the sink", seen.contains(0x31))
        controller.close()
    }

    /** The adapter's own packet count bounds the window: its buffer is the one that overflows. */
    @Test fun theAdaptersOwnPacketCountBoundsTheWindow() {
        val transport = FakeHciTransport()
        val diagnostics = CopyOnWriteArrayList<String>()
        val controller = HciController(
            transport,
            {},
            {},
            { line -> diagnostics += line },
            aclPermitTimeoutMillis = 300,
        )
        controller.start()
        // Read_Buffer_Size: 251 octets per packet, and this radio holds two of them.
        controller.applyBufferSize(aclMtu = 251, maxPackets = 2)
        assertEquals(247, controller.aclPayloadBytes)
        repeat(2) { controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x01)) }

        val failure = runCatching {
            controller.sendAcl(handle = 0x0B, packetBoundary = 0, payload = byteArrayOf(0x02))
        }.exceptionOrNull()

        assertTrue("the third packet must wait for a window of two", failure is IOException)
        controller.close()
    }
}
