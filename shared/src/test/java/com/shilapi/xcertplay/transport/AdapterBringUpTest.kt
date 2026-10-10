package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.hci.AdapterBluetoothState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterBringUpTest {
    @Test fun anUnpluggedAdapterIsNotAStartup() {
        val report = AdapterBringUpAssessment.assess(seen(plugged = false))
        assertEquals(AdapterBringUpStep.NOT_PLUGGED, report.step)
        assertFalse(report.startupEffective)
        assertFalse(report.flowEffective)
    }

    @Test fun aPluggedAdapterWithNoPermissionIsStuckOnTheUsbPrompt() {
        val report = AdapterBringUpAssessment.assess(seen(permissionGranted = false))
        assertEquals(AdapterBringUpStep.USB_PERMISSION, report.step)
        assertFalse(report.startupEffective)
    }

    @Test fun aRefusedUsbPermissionStaysOnThatStep() {
        val report = AdapterBringUpAssessment.assess(
            seen(state = AdapterBluetoothState.FAILED, reason = "usb-permission-denied"),
        )
        assertEquals(AdapterBringUpStep.USB_PERMISSION, report.step)
    }

    @Test fun openClaimAndEndpointFailuresNameThatStep() {
        assertEquals(
            AdapterBringUpStep.OPEN_DEVICE,
            AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.FAILED, reason = "open-device")).step,
        )
        assertEquals(
            AdapterBringUpStep.CLAIM_INTERFACE,
            AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.FAILED, reason = "claim-interface")).step,
        )
        assertEquals(
            AdapterBringUpStep.NO_ENDPOINTS,
            AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.FAILED, reason = "endpoints")).step,
        )
    }

    @Test fun resetAndAFailedAdvertiseAreNotAnEffectiveStartup() {
        val resetting = AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.RESETTING, reason = "start"))
        assertEquals(AdapterBringUpStep.RESETTING, resetting.step)
        assertFalse(resetting.startupEffective)

        val advertise = AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.FAILED, reason = "write-eir"))
        assertEquals(AdapterBringUpStep.ADVERTISE, advertise.step)
        assertFalse(advertise.startupEffective)
        assertFalse(advertise.flowEffective)
    }

    @Test fun permissionWithoutAStateMeansStartupHasNotBegun() {
        val report = AdapterBringUpAssessment.assess(seen())
        assertEquals(AdapterBringUpStep.NOT_STARTED, report.step)
        assertFalse(report.startupEffective)
    }

    @Test fun broadcastingWithoutAPhoneHasStartedAndIsStuckOnPairing() {
        val report = AdapterBringUpAssessment.assess(
            seen(state = AdapterBluetoothState.BROADCASTING, reason = "advertising"),
        )
        assertEquals(AdapterBringUpStep.WAITING_FOR_PAIR, report.step)
        assertTrue(report.startupEffective)
        assertFalse(report.flowEffective)
    }

    @Test fun broadcastingWithAPairedPhoneMeansTheFlowIsUsable() {
        val report = AdapterBringUpAssessment.assess(
            seen(state = AdapterBluetoothState.BROADCASTING, reason = "advertising", paired = true),
        )
        assertEquals(AdapterBringUpStep.PAIRED, report.step)
        assertTrue(report.startupEffective)
        assertTrue(report.flowEffective)
    }

    @Test fun aStalledPairAndLaterLinkFailuresStayOnThatStep() {
        assertEquals(
            AdapterBringUpStep.PAIRING_STALLED,
            AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.PAIRING_STALLED, reason = "timeout")).step,
        )
        val acl = AdapterBringUpAssessment.assess(
            seen(state = AdapterBluetoothState.ACL_FAILED, reason = "authentication", paired = true),
        )
        assertEquals(AdapterBringUpStep.ACL_AUTHENTICATION, acl.step)
        assertTrue(acl.startupEffective)
        assertFalse(acl.flowEffective)
        assertEquals(
            AdapterBringUpStep.SDP,
            AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.SDP_NO_IAP2_SERVICE, reason = "sdp")).step,
        )
        assertEquals(
            AdapterBringUpStep.RFCOMM,
            AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.RFCOMM_FAILED, reason = "rfcomm")).step,
        )
    }

    @Test fun aKnownOffHotspotIsNotReportedAsAFailedBroadcast() {
        val report = AdapterBringUpAssessment.assess(
            seen(state = AdapterBluetoothState.UNADVERTISED, reason = "hotspot-off"),
        )
        assertEquals(AdapterBringUpStep.HOTSPOT_OFF, report.step)
        assertFalse(report.startupEffective)
        assertFalse(report.flowEffective)
    }

    @Test fun anUnknownHotspotStateStillAllowsTheNameToBeBroadcast() {
        assertTrue(AdapterDiscovery.shouldAdvertise(null))
        assertTrue(AdapterDiscovery.shouldAdvertise(true))
        assertFalse(AdapterDiscovery.shouldAdvertise(false))
    }

    @Test fun theAdapterRunsOnlyAfterTheUserChoosesIt() {
        assertFalse(AdapterDiscovery.runs(null))
        assertFalse(AdapterDiscovery.runs(WirelessBluetoothHop.CAR))
        assertTrue(AdapterDiscovery.runs(WirelessBluetoothHop.USB_ADAPTER))
    }

    @Test fun anOpenIap2StreamMeansTheFlowIsEffective() {
        val report = AdapterBringUpAssessment.assess(seen(state = AdapterBluetoothState.READY, reason = "iap2 stream open", paired = true))
        assertEquals(AdapterBringUpStep.READY, report.step)
        assertTrue(report.startupEffective)
        assertTrue(report.flowEffective)
    }

    private fun seen(
        plugged: Boolean = true,
        permissionGranted: Boolean = true,
        state: AdapterBluetoothState? = null,
        reason: String? = null,
        paired: Boolean = false,
    ) = AdapterObservation(plugged, permissionGranted, state, reason, paired)
}

class WirelessReadinessCheckTest {
    private val paired = AdapterBringUp(AdapterBringUpStep.PAIRED, startupEffective = true, flowEffective = true, reason = null)
    private val waiting = AdapterBringUp(AdapterBringUpStep.WAITING_FOR_PAIR, startupEffective = true, flowEffective = false, reason = null)

    @Test fun aReadyCarPathHasNoGaps() {
        val report = WirelessReadinessCheck.assess(input(hop = WirelessBluetoothHop.CAR, phoneChosen = true))
        assertTrue(report.ready)
    }

    @Test fun missingHotspotDetailsAreListedBeforeTheRadio() {
        val report = WirelessReadinessCheck.assess(
            input(credentialsSaved = false, hotspotOn = false, hop = null),
        )
        assertEquals(
            listOf(WirelessGap.HOTSPOT_CREDENTIALS, WirelessGap.HOTSPOT_OFF, WirelessGap.RADIO_NOT_CHOSEN),
            report.gaps,
        )
    }

    @Test fun theCarRadioOffOrWithoutAPhoneIsItsOwnGap() {
        val off = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.CAR, carBluetoothOn = false, phoneChosen = false),
        )
        assertEquals(listOf(WirelessGap.CAR_BLUETOOTH_OFF, WirelessGap.PHONE_NOT_CHOSEN), off.gaps)
    }

    @Test fun anAdapterThatHasNotFinishedBlocksAndKeepsTheStuckStep() {
        val report = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.USB_ADAPTER, adapterPlugged = true, adapter = waiting),
        )
        assertEquals(listOf(WirelessGap.DONGLE_NOT_READY), report.gaps)
        assertEquals(AdapterBringUpStep.WAITING_FOR_PAIR, report.adapter?.step)
    }

    @Test fun anEffectiveAdapterStillAsksForThePhoneToBeChosen() {
        val report = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.USB_ADAPTER, phoneChosen = false, adapterPlugged = true, adapter = paired),
        )
        assertEquals(listOf(WirelessGap.PHONE_NOT_CHOSEN), report.gaps)
    }

    @Test fun aPluggedAdapterIsReportedEvenWhenTheCarRadioWasChosen() {
        val report = WirelessReadinessCheck.assess(
            input(hop = WirelessBluetoothHop.CAR, phoneChosen = true, adapterPlugged = true, adapter = waiting),
        )
        assertTrue(report.ready)
        assertEquals(AdapterBringUpStep.WAITING_FOR_PAIR, report.adapter?.step)
    }

    private fun input(
        credentialsSaved: Boolean = true,
        hotspotOn: Boolean = true,
        hop: WirelessBluetoothHop?,
        phoneChosen: Boolean = false,
        carBluetoothOn: Boolean = true,
        adapterPlugged: Boolean = false,
        adapter: AdapterBringUp? = null,
    ) = WirelessReadinessInput(credentialsSaved, hotspotOn, hop, phoneChosen, carBluetoothOn, adapterPlugged, adapter)
}
