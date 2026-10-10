package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.hci.ActionsHciHost
import com.shilapi.xcertplay.transport.hci.AdapterBluetoothState
import com.shilapi.xcertplay.transport.hci.FakeHciTransport
import com.shilapi.xcertplay.transport.hci.HciCommands
import com.shilapi.xcertplay.transport.hci.commandComplete
import com.shilapi.xcertplay.transport.hci.linkKeyNotificationEvent
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The adapter-side seam: the same three answers the Android-side link gives, taken from the adapter's own HCI
 * link instead of the head unit's Bluetooth stack.
 */
class Iap2BluetoothLinkTest {
    @get:Rule val folder = TemporaryFolder()

    /** Plays the adapter's firmware: bring-up commands succeed, Read_BD_ADDR names the adapter. */
    private fun fakeAdapter(): FakeHciTransport = FakeHciTransport().apply {
        onCommand = { packet ->
            val opcode = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
            when (opcode) {
                READ_BD_ADDR ->
                    enqueueEvent(commandComplete(opcode, 0, HciCommands.formatAddress(ADAPTER)))

                READ_LOCAL_VERSION -> enqueueEvent(
                    commandComplete(opcode, 0, byteArrayOf(0x06, 0x00, 0x00, 0x0A, 0x0F, 0x00)),
                )

                READ_BUFFER_SIZE -> enqueueEvent(
                    commandComplete(opcode, 0, byteArrayOf(0xFB.toByte(), 0x00, 0x1E, 0x08, 0x00, 0x00, 0x00)),
                )

                else -> enqueueEvent(commandComplete(opcode, 0))
            }
        }
    }

    private fun startedHost(
        transport: FakeHciTransport,
        states: CopyOnWriteArrayList<AdapterBluetoothState> = CopyOnWriteArrayList(),
    ): ActionsHciHost = ActionsHciHost(
        transport,
        File(folder.root, "linkkeys.tsv"),
        { state, _ -> states += state },
    ).also { assertTrue(it.start()) }

    /** Lets the reader thread consume the Link_Key_Notification and name the phone. */
    private fun awaitPairing(host: ActionsHciHost): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (host.pairedTarget() != null) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun pairedHost(transport: FakeHciTransport): ActionsHciHost =
        startedHost(transport).also {
            transport.enqueueEvent(linkKeyNotificationEvent(PHONE, ByteArray(16) { 0x5A }, keyType = 5))
            assertTrue("the phone should show up as the paired target", awaitPairing(it))
        }

    @Test fun localAddressIsTheAdaptersOwn() {
        val host = startedHost(fakeAdapter())

        assertEquals(ADAPTER, AdapterIap2BluetoothLink(host).localAddress)
        host.close()
    }

    @Test fun nothingPairedYetIsAnEmptyTargetRatherThanAnError() {
        val host = startedHost(fakeAdapter())

        assertNull(AdapterIap2BluetoothLink(host).target())
        host.close()
    }

    @Test fun thePhoneTheAdapterPairedWithIsTheTarget() {
        val transport = fakeAdapter()
        val host = pairedHost(transport)

        val target = AdapterIap2BluetoothLink(host).target()

        assertEquals(PHONE, target?.address)
        host.close()
    }

    @Test fun aConfiguredAddressMatchingThePairedPhoneIsAccepted() {
        val host = pairedHost(fakeAdapter())

        val target = AdapterIap2BluetoothLink(host, configuredAddress = PHONE).target()

        assertEquals(PHONE, target?.address)
        host.close()
    }

    @Test fun aConfiguredAddressTheAdapterNeverPairedWithStillFails() {
        val host = pairedHost(fakeAdapter())
        val link = AdapterIap2BluetoothLink(host, configuredAddress = "AA:BB:CC:DD:EE:FF")

        val failure = runCatching { link.target() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(
            "The selected iPhone is no longer paired. Choose it again in DiPlay.",
            failure?.message,
        )
        host.close()
    }

    @Test fun openWithoutAPairedPhoneFailsThroughTheAdapter() {
        val states = CopyOnWriteArrayList<AdapterBluetoothState>()
        val host = startedHost(fakeAdapter(), states)
        val link = AdapterIap2BluetoothLink(host)

        val failure = runCatching { link.open() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(states.contains(AdapterBluetoothState.NOT_PAIRED))
        host.close()
    }

    /** Stands in for the car's own Bluetooth hop, which is what a run falls back to. */
    private class CarLink : Iap2BluetoothLink {
        override val localAddress: String = "F0:00:00:00:00:01"

        override fun target(): PairedTarget = PairedTarget("iPhone", "AA:00:00:00:00:01")

        override fun open(): BlockingDuplexByteStream =
            throw IOException("the car link is not opened by these tests")
    }

    @Test fun anAdapterThatHasPairedAPhoneTakesOverTheHop() {
        val host = pairedHost(fakeAdapter())
        var carCalls = 0
        val diagnostics = CopyOnWriteArrayList<String>()

        val link = Iap2BluetoothLinks.chooseForWireless(
            WirelessBluetoothHop.USB_ADAPTER, host, null,
            onDiagnostic = { line -> diagnostics += line }, externalRouteEnabled = true,
        ) {
            carCalls += 1
            CarLink()
        }

        assertTrue(link is AdapterIap2BluetoothLink)
        assertEquals(0, carCalls)
        assertTrue(diagnostics.any { it.contains("hop=usb-adapter") && it.contains(PHONE) })
        host.close()
    }

    @Test fun choosingTheAdapterKeepsItEvenWhenNothingIsPairedYet() {
        val host = startedHost(fakeAdapter())
        var carCalls = 0
        val diagnostics = CopyOnWriteArrayList<String>()

        val link = Iap2BluetoothLinks.chooseForWireless(
            WirelessBluetoothHop.USB_ADAPTER, host, null,
            onDiagnostic = { line -> diagnostics += line }, externalRouteEnabled = true,
        ) {
            carCalls += 1
            CarLink()
        }

        assertTrue(link is AdapterIap2BluetoothLink)
        assertEquals(0, carCalls)
        assertTrue(diagnostics.any { it.contains("hop=usb-adapter") && it.contains("unpaired") })
        host.close()
    }

    @Test fun theMostRecentlyPairedPhoneIsTheOneChosenWhenSeveralAreKnown() {
        File(folder.root, "linkkeys.tsv").writeText(
            "$OLDER_PHONE\t5\t${"5A".repeat(16)}\n$NEWER_PHONE\t5\t${"5A".repeat(16)}\n",
        )
        val host = startedHost(fakeAdapter())

        assertEquals(NEWER_PHONE, AdapterIap2BluetoothLink(host).target()?.address)
        host.close()
    }

    @Test fun choosingTheCarIgnoresAPairedAdapter() {
        val host = pairedHost(fakeAdapter())
        var carCalls = 0

        val link = Iap2BluetoothLinks.chooseForWireless(WirelessBluetoothHop.CAR, host, null, {}) {
            carCalls += 1
            CarLink()
        }

        assertTrue(link is CarLink)
        assertEquals(1, carCalls)
        host.close()
    }

    @Test fun choosingTheCarWithNoAdapterLeavesTheCarInCharge() {
        var carCalls = 0

        val link = Iap2BluetoothLinks.chooseForWireless(WirelessBluetoothHop.CAR, null, null, {}) {
            carCalls += 1
            CarLink()
        }

        assertTrue(link is CarLink)
        assertEquals(1, carCalls)
    }

    @Test fun choosingTheAdapterWhenItIsNotRunningDoesNotFallBackToTheCar() {
        var carCalls = 0

        val failure = runCatching {
            Iap2BluetoothLinks.chooseForWireless(
                WirelessBluetoothHop.USB_ADAPTER, null, null, {}, externalRouteEnabled = true,
            ) {
                carCalls += 1
                CarLink()
            }
        }.exceptionOrNull()

        assertEquals(0, carCalls)
        assertTrue(failure is IOException)
    }

    /** What a shipping build does: the radio is gone, so a stale choice of it can only fail. */
    @Test fun choosingTheAdapterWithTheRouteOffFailsAndNeverReachesTheCar() {
        var carCalls = 0

        val failure = runCatching {
            Iap2BluetoothLinks.chooseForWireless(
                WirelessBluetoothHop.USB_ADAPTER, null, null, {}, externalRouteEnabled = false,
            ) {
                carCalls += 1
                CarLink()
            }
        }.exceptionOrNull()

        assertEquals(0, carCalls)
        assertEquals("External Bluetooth route is off", failure?.message)
    }

    @Test fun theConfiguredPhoneIsTheOneTheAdapterMustHavePairedWith() {
        val host = pairedHost(fakeAdapter())

        val link = AdapterIap2BluetoothLink(host, configuredAddress = "AA:BB:CC:DD:EE:FF")

        val failure = runCatching { link.target() }.exceptionOrNull()
        assertEquals(
            "The selected iPhone is no longer paired. Choose it again in DiPlay.",
            failure?.message,
        )
        host.close()
    }

    private companion object {
        const val ADAPTER = "F4:4E:FC:F8:8B:36"
        const val PHONE = "CC:60:23:D7:2F:5B"
        const val OLDER_PHONE = PHONE
        const val NEWER_PHONE = "11:22:33:44:55:66"

        const val READ_BD_ADDR = 0x1009
        const val READ_LOCAL_VERSION = 0x1001
        const val READ_BUFFER_SIZE = 0x1005
    }
}
