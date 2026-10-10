package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBluetoothRadiosTest {
    private fun face(
        index: Int,
        interfaceClass: Int = UsbBluetoothRadios.VENDOR_SPECIFIC,
        subclass: Int = 0,
        protocol: Int = 0,
        interruptIn: Int? = null,
        bulkIn: Int? = null,
        bulkOut: Int? = null,
    ) = UsbHciInterface(index, interfaceClass, subclass, protocol, interruptIn, bulkIn, bulkOut)

    /** The shape every HCI-over-USB radio has: events on an interrupt IN, ACL on the bulk pair. */
    private fun hciFace(index: Int = 0, vendorClass: Boolean = true) = face(
        index = index,
        interfaceClass = if (vendorClass) UsbBluetoothRadios.VENDOR_SPECIFIC
        else UsbBluetoothRadios.WIRELESS_CONTROLLER,
        subclass = if (vendorClass) 0 else UsbBluetoothRadios.RF_CONTROLLER,
        protocol = if (vendorClass) 0 else UsbBluetoothRadios.BLUETOOTH_PROTOCOL,
        interruptIn = 0x81,
        bulkIn = 0x82,
        bulkOut = 0x02,
    )

    @Test fun aRadioThatAnnouncesBluetoothIsACandidateWhateverItsEndpointsLookLike() {
        // A two-interface radio: the command interface announces Bluetooth and carries only the event endpoint.
        val commandFace = face(
            index = 0,
            interfaceClass = UsbBluetoothRadios.WIRELESS_CONTROLLER,
            subclass = UsbBluetoothRadios.RF_CONTROLLER,
            protocol = UsbBluetoothRadios.BLUETOOTH_PROTOCOL,
            interruptIn = 0x81,
            bulkOut = 0x02,
        )

        assertTrue(UsbBluetoothRadios.isCandidate(0x0A12, 0x0001, listOf(commandFace)))
    }

    /**
     * The adapter in the car does not say it is a Bluetooth radio — it answers vendor control requests, which is
     * why the probe needed them. Recognising it by the endpoints it exposes is what keeps it working without a
     * model list.
     */
    @Test fun aQuietRadioIsRecognisedByItsEndpoints() {
        assertTrue(UsbBluetoothRadios.isCandidate(0x10D7, 0xB012, listOf(hciFace())))
    }

    @Test fun somethingThatMerelyHasAnInterruptEndpointIsNotARadio() {
        // A HID-style interface: an interrupt IN and nothing to carry ACL over.
        val hid = face(index = 0, interfaceClass = 0x03, interruptIn = 0x81)

        assertFalse(UsbBluetoothRadios.isCandidate(0x1234, 0x5678, listOf(hid)))
    }

    /** DiPlay's own I2C bridge; taking it for a radio would break wired CarPlay. */
    @Test fun theDigitalSerialBridgeIsNeverARadio() {
        assertFalse(
            UsbBluetoothRadios.isCandidate(
                UsbBluetoothRadios.CH341_VENDOR_ID,
                UsbBluetoothRadios.CH341_PRODUCT_ID,
                listOf(hciFace()),
            ),
        )
    }

    /**
     * The iPhone is a USB device in this same process — the wired path talks to it — and an Apple interface carries
     * an interrupt IN with a bulk pair, which is the shape this test otherwise accepts. Taking it would claim the
     * phone out from under the wired handshake. Like the CH341 it is excluded by name, not by a list of the radios
     * that are allowed.
     */
    @Test fun theIphoneIsNeverTakenForARadio() {
        assertFalse(UsbBluetoothRadios.isCandidate(UsbBluetoothRadios.IPHONE_VENDOR_ID, 0x12A8, listOf(hciFace())))
    }

    @Test fun theCommandInterfaceIsTheOneThatCarriesTheEventEndpoint() {
        val commandFace = face(
            index = 0,
            interfaceClass = UsbBluetoothRadios.WIRELESS_CONTROLLER,
            subclass = UsbBluetoothRadios.RF_CONTROLLER,
            protocol = UsbBluetoothRadios.BLUETOOTH_PROTOCOL,
            interruptIn = 0x81,
            bulkOut = 0x02,
        )
        val aclFace = hciFace(index = 1, vendorClass = false).copy(interruptIn = null)

        val chosen = UsbBluetoothRadios.controlInterface(listOf(commandFace, aclFace))

        assertEquals(0, chosen?.index)
    }

    @Test fun aclComesFromTheOtherInterfaceWhenTheRadioHasTwo() {
        val commandFace = face(
            index = 0,
            interfaceClass = UsbBluetoothRadios.WIRELESS_CONTROLLER,
            subclass = UsbBluetoothRadios.RF_CONTROLLER,
            protocol = UsbBluetoothRadios.BLUETOOTH_PROTOCOL,
            interruptIn = 0x81,
            bulkOut = 0x02,
        )
        val aclFace = face(index = 1, interfaceClass = 0x0A, bulkIn = 0x82, bulkOut = 0x03)

        val chosen = UsbBluetoothRadios.controlInterface(listOf(commandFace, aclFace))

        assertEquals(1, UsbBluetoothRadios.aclInterface(listOf(commandFace, aclFace), chosen!!).index)
    }

    @Test fun aSingleInterfaceRadioKeepsItsAclOnTheSameInterface() {
        val only = hciFace()

        assertEquals(only, UsbBluetoothRadios.aclInterface(listOf(only), only))
    }

    @Test fun nothingUsableMeansNoInterfaceRatherThanAGuess() {
        assertNull(UsbBluetoothRadios.controlInterface(listOf(face(index = 0, interfaceClass = 0x08))))
    }
}
