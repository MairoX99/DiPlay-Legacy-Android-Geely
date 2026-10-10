package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.hci.AdapterBluetoothState

/** Which Bluetooth radio a wireless connection is supposed to use. The user picks this. */
enum class WirelessBluetoothHop(val key: String) {
    USB_ADAPTER("usb_adapter"),
    CAR("car");

    companion object {
        fun fromKey(key: String?): WirelessBluetoothHop? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What was seen of the USB Bluetooth adapter.
 *
 * [state] is whatever the adapter last reported. Null means it has not reported anything yet.
 */
data class AdapterObservation(
    val plugged: Boolean,
    val permissionGranted: Boolean,
    val state: AdapterBluetoothState?,
    val reason: String?,
    val paired: Boolean,
)

/** The step the adapter hop is on. The screen says this step when the hop is not usable yet. */
enum class AdapterBringUpStep {
    NOT_PLUGGED,
    USB_PERMISSION,
    OPEN_DEVICE,
    CLAIM_INTERFACE,
    NO_ENDPOINTS,
    NOT_STARTED,
    RESETTING,
    ADVERTISE,
    HOTSPOT_OFF,
    WAITING_FOR_PAIR,
    PAIRING,
    PAIRING_STALLED,
    NOT_PAIRED,
    ACL_CONNECT,
    ACL_AUTHENTICATION,
    SDP,
    RFCOMM,
    READY,
    PAIRED,
    FAILED,
}

/**
 * [startupEffective] means reset and advertise finished.
 * [flowEffective] means that startup is up and a phone is paired, or the iAP2 stream is already open.
 * Anything short of that is stuck on [step].
 */
data class AdapterBringUp(
    val step: AdapterBringUpStep,
    val startupEffective: Boolean,
    val flowEffective: Boolean,
    val reason: String?,
)

/**
 * The adapter should say its name unless the car hotspot is known to be off.
 * A firmware that hides the hotspot state returns null, and that must not block pairing.
 */
object AdapterDiscovery {
    fun shouldAdvertise(hotspotEnabled: Boolean?): Boolean = hotspotEnabled != false

    /** The USB adapter stack runs only after the user has chosen that radio. */
    fun runs(hop: WirelessBluetoothHop?): Boolean = hop == WirelessBluetoothHop.USB_ADAPTER
}

object AdapterBringUpAssessment {
    fun assess(observed: AdapterObservation): AdapterBringUp {
        if (!observed.plugged || observed.reason == "adapter-unplugged") {
            return stuck(AdapterBringUpStep.NOT_PLUGGED, observed.reason)
        }
        if (!observed.permissionGranted || observed.reason == "usb-permission-denied") {
            return stuck(AdapterBringUpStep.USB_PERMISSION, observed.reason)
        }
        return when (observed.state) {
            null, AdapterBluetoothState.IDLE -> stuck(AdapterBringUpStep.NOT_STARTED, observed.reason)
            AdapterBluetoothState.RESETTING -> stuck(AdapterBringUpStep.RESETTING, observed.reason)
            AdapterBluetoothState.UNADVERTISED ->
                if (observed.reason == "hotspot-off") stuck(AdapterBringUpStep.HOTSPOT_OFF, observed.reason)
                else stuck(AdapterBringUpStep.ADVERTISE, observed.reason)
            AdapterBluetoothState.FAILED -> stuck(failedStep(observed.reason), observed.reason)
            AdapterBluetoothState.BROADCASTING ->
                if (observed.paired) flowing(AdapterBringUpStep.PAIRED, observed.reason)
                else started(AdapterBringUpStep.WAITING_FOR_PAIR, observed.reason)
            AdapterBluetoothState.PAIRING -> started(AdapterBringUpStep.PAIRING, observed.reason)
            AdapterBluetoothState.PAIRING_STALLED -> started(AdapterBringUpStep.PAIRING_STALLED, observed.reason)
            AdapterBluetoothState.NOT_PAIRED -> started(AdapterBringUpStep.NOT_PAIRED, observed.reason)
            AdapterBluetoothState.ACL_FAILED -> started(
                if (observed.reason == "authentication") AdapterBringUpStep.ACL_AUTHENTICATION
                else AdapterBringUpStep.ACL_CONNECT,
                observed.reason,
            )
            AdapterBluetoothState.SDP_NO_IAP2_SERVICE -> started(AdapterBringUpStep.SDP, observed.reason)
            AdapterBluetoothState.RFCOMM_FAILED -> started(AdapterBringUpStep.RFCOMM, observed.reason)
            AdapterBluetoothState.READY -> flowing(AdapterBringUpStep.READY, observed.reason)
        }
    }

    private fun failedStep(reason: String?): AdapterBringUpStep = when (reason) {
        "open-device" -> AdapterBringUpStep.OPEN_DEVICE
        "claim-interface" -> AdapterBringUpStep.CLAIM_INTERFACE
        "endpoints" -> AdapterBringUpStep.NO_ENDPOINTS
        "hci-reset", "read-bd-addr" -> AdapterBringUpStep.RESETTING
        "advertise", "write-local-name", "write-eir", "write-cod", "write-scan-enable", "write-ssp-mode" ->
            AdapterBringUpStep.ADVERTISE
        "usb-permission-denied" -> AdapterBringUpStep.USB_PERMISSION
        else -> AdapterBringUpStep.FAILED
    }

    private fun stuck(step: AdapterBringUpStep, reason: String?) =
        AdapterBringUp(step, startupEffective = false, flowEffective = false, reason = reason)

    private fun started(step: AdapterBringUpStep, reason: String?) =
        AdapterBringUp(step, startupEffective = true, flowEffective = false, reason = reason)

    private fun flowing(step: AdapterBringUpStep, reason: String?) =
        AdapterBringUp(step, startupEffective = true, flowEffective = true, reason = reason)
}

enum class WirelessGap {
    HOTSPOT_CREDENTIALS,
    HOTSPOT_OFF,
    RADIO_NOT_CHOSEN,
    CAR_BLUETOOTH_OFF,
    PHONE_NOT_CHOSEN,
    DONGLE_NOT_READY,
}

data class WirelessReadinessInput(
    val credentialsSaved: Boolean,
    val hotspotOn: Boolean,
    val hop: WirelessBluetoothHop?,
    val phoneChosen: Boolean,
    val carBluetoothOn: Boolean,
    val adapterPlugged: Boolean,
    val adapter: AdapterBringUp?,
)

data class WirelessReadiness(
    val gaps: List<WirelessGap>,
    val adapter: AdapterBringUp?,
) {
    val ready: Boolean get() = gaps.isEmpty()
}

object WirelessReadinessCheck {
    fun assess(input: WirelessReadinessInput): WirelessReadiness {
        val gaps = mutableListOf<WirelessGap>()
        if (!input.credentialsSaved) gaps += WirelessGap.HOTSPOT_CREDENTIALS
        if (!input.hotspotOn) gaps += WirelessGap.HOTSPOT_OFF
        when (input.hop) {
            null -> gaps += WirelessGap.RADIO_NOT_CHOSEN
            WirelessBluetoothHop.CAR -> {
                if (!input.carBluetoothOn) gaps += WirelessGap.CAR_BLUETOOTH_OFF
                if (!input.phoneChosen) gaps += WirelessGap.PHONE_NOT_CHOSEN
            }
            WirelessBluetoothHop.USB_ADAPTER -> {
                val bringUp = input.adapter
                if (bringUp == null || !bringUp.flowEffective) gaps += WirelessGap.DONGLE_NOT_READY
                else if (!input.phoneChosen) gaps += WirelessGap.PHONE_NOT_CHOSEN
            }
        }
        val adapter = when {
            input.hop == WirelessBluetoothHop.USB_ADAPTER -> input.adapter
            input.adapterPlugged -> input.adapter
            else -> null
        }
        return WirelessReadiness(gaps, adapter)
    }
}
