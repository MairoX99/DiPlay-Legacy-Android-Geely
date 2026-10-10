// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.orchestration

/**
 * The rungs of a bring-up attempt, in the order [CarPlayController] actually climbs them.
 *
 * A rung is one thing the driver can watch finish. [CarPlayStatus] is the controller's own
 * vocabulary — twenty-two values that move with the flow — so the mapping from status to rung
 * lives here, in one total function, rather than being re-derived by whichever screen happens to
 * be drawing. It was previously derived in the host activity behind an `else` branch, which
 * silently reported every unlisted status as the last rung.
 *
 * The order matters and is not the order the copy was written in: MFi authentication runs before
 * anything else, including the iPhone it authenticates, and on the wireless path the AirPlay
 * network transport is attached before the Bluetooth data channel is opened.
 */
enum class ConnectionStep {
    /** The accessory certificate exchange, first on both transports. */
    MFI,

    /** Wired: discovering the iPhone on the USB bus and selecting its configuration. */
    DEVICE,

    /** Wireless: the car hotspot the iPhone will join. */
    HOTSPOT,

    /** Wireless: the iPhone must be bonded over Bluetooth before it can be reached. */
    PAIRING,

    /** Wireless: attaching the AirPlay network transport and its listener. */
    NETWORK,

    /** Opening the iAP2 data channel to the iPhone. */
    DATA_LINK,

    /** The CarPlay session itself. */
    SESSION,
}

enum class ConnectionStepState { PENDING, ACTIVE, DONE, FAILED }

data class ConnectionStepRow(val step: ConnectionStep, val state: ConnectionStepState)

/**
 * A ladder and where the attempt stands on it.
 *
 * [index] is the rung in progress. It is [ladder]`.size` once every rung is behind the attempt,
 * which is how a running session renders: no rung is in progress because all of them are done.
 */
data class ConnectionProgress(
    val ladder: List<ConnectionStep>,
    val index: Int,
    val failed: Boolean,
) {
    val rows: List<ConnectionStepRow>
        get() = ladder.mapIndexed { rung, step -> ConnectionStepRow(step, stateOf(rung)) }

    fun stateOf(rung: Int): ConnectionStepState = when {
        rung < 0 -> ConnectionStepState.PENDING
        rung < index -> ConnectionStepState.DONE
        rung > index -> ConnectionStepState.PENDING
        failed -> ConnectionStepState.FAILED
        else -> ConnectionStepState.ACTIVE
    }
}

fun connectionLadder(transport: CarPlayTransport): List<ConnectionStep> = when (transport) {
    CarPlayTransport.WIRED -> listOf(
        ConnectionStep.MFI,
        ConnectionStep.DEVICE,
        ConnectionStep.DATA_LINK,
        ConnectionStep.SESSION,
    )

    CarPlayTransport.WIRELESS -> listOf(
        ConnectionStep.MFI,
        ConnectionStep.HOTSPOT,
        ConnectionStep.PAIRING,
        ConnectionStep.NETWORK,
        ConnectionStep.DATA_LINK,
        ConnectionStep.SESSION,
    )
}

/** True once the session is up, or was up and has since ended. */
fun connectionComplete(status: CarPlayStatus): Boolean = when (status) {
    CarPlayStatus.RunningControl,
    CarPlayStatus.WirelessActive,
    CarPlayStatus.ControlEnded,
    -> true

    else -> false
}

/**
 * The rung a status belongs to, or null for a status the given transport never reports.
 *
 * Both `when`s are exhaustive over [CarPlayStatus] on purpose: a status added to the controller
 * has to be placed on both ladders, or this file stops compiling, which is the only way the
 * screen and the flow stay in step.
 */
fun connectionStepOf(status: CarPlayStatus, transport: CarPlayTransport): ConnectionStep? = when (transport) {
    CarPlayTransport.WIRED -> when (status) {
        CarPlayStatus.DiscoveringMfi,
        CarPlayStatus.WaitingForMfi,
        CarPlayStatus.RequestingMfiPermission,
        CarPlayStatus.MfiReady,
        -> ConnectionStep.MFI

        CarPlayStatus.DiscoveringIphone,
        CarPlayStatus.WaitingForIphone,
        CarPlayStatus.RequestingIphonePermission,
        CarPlayStatus.WaitingForReenumeration,
        CarPlayStatus.SelectingConfiguration,
        -> ConnectionStep.DEVICE

        CarPlayStatus.OpeningDataPaths,
        CarPlayStatus.Pairing,
        CarPlayStatus.AttachingNetwork,
        -> ConnectionStep.DATA_LINK

        CarPlayStatus.ConnectingControl,
        CarPlayStatus.RunningControl,
        CarPlayStatus.ControlEnded,
        -> ConnectionStep.SESSION

        // Wireless-only rungs; a wired attempt never reports them.
        CarPlayStatus.StartingHotspot,
        is CarPlayStatus.HotspotReady,
        CarPlayStatus.WaitingForPairedIphone,
        CarPlayStatus.ConnectingBluetooth,
        CarPlayStatus.RunningWireless,
        CarPlayStatus.WirelessActive,
        -> null

        is CarPlayStatus.Failed -> null
    }

    CarPlayTransport.WIRELESS -> when (status) {
        CarPlayStatus.DiscoveringMfi,
        CarPlayStatus.WaitingForMfi,
        CarPlayStatus.RequestingMfiPermission,
        CarPlayStatus.MfiReady,
        -> ConnectionStep.MFI

        CarPlayStatus.StartingHotspot,
        is CarPlayStatus.HotspotReady,
        -> ConnectionStep.HOTSPOT

        CarPlayStatus.WaitingForPairedIphone -> ConnectionStep.PAIRING

        // The controller attaches the network transport (attachWireless + Bonjour) before it opens
        // RFCOMM, so this rung sits ahead of the Bluetooth one. The copy used to have these the
        // other way round and lit the wrong row for the whole middle of the attempt.
        CarPlayStatus.AttachingNetwork -> ConnectionStep.NETWORK

        CarPlayStatus.ConnectingBluetooth,
        CarPlayStatus.RunningWireless,
        -> ConnectionStep.DATA_LINK

        CarPlayStatus.WirelessActive -> ConnectionStep.SESSION

        // Wired-only rungs; a wireless attempt reaches the iPhone over the network instead.
        CarPlayStatus.DiscoveringIphone,
        CarPlayStatus.WaitingForIphone,
        CarPlayStatus.RequestingIphonePermission,
        CarPlayStatus.WaitingForReenumeration,
        CarPlayStatus.SelectingConfiguration,
        CarPlayStatus.OpeningDataPaths,
        CarPlayStatus.Pairing,
        CarPlayStatus.ConnectingControl,
        CarPlayStatus.RunningControl,
        CarPlayStatus.ControlEnded,
        -> null

        is CarPlayStatus.Failed -> null
    }
}

/**
 * A ladder with its first rung in progress and nothing reported yet — what the screen shows
 * between choosing a transport and the controller's first status.
 */
fun connectionProgressInitial(transport: CarPlayTransport): ConnectionProgress =
    ConnectionProgress(connectionLadder(transport), 0, failed = false)

/**
 * Where the attempt stands.
 *
 * [failedAt] is the rung the attempt was on when it failed: [CarPlayStatus.Failed] carries the reason
 * and an optional detail, neither of which names a rung, so the caller remembers the last rung a
 * non-failed status named and hands it back. A failure with no remembered rung is reported on the
 * first rung rather than on none, because a ladder with nothing lit says less than one that is wrong
 * by a rung.
 */
fun connectionProgress(
    status: CarPlayStatus,
    transport: CarPlayTransport,
    failedAt: ConnectionStep? = null,
): ConnectionProgress {
    val ladder = connectionLadder(transport)
    if (status is CarPlayStatus.Failed) {
        val at = failedAt?.let { ladder.indexOf(it) } ?: 0
        return ConnectionProgress(ladder, if (at < 0) 0 else at, failed = true)
    }
    if (connectionComplete(status)) return ConnectionProgress(ladder, ladder.size, failed = false)
    val step = connectionStepOf(status, transport)
    return ConnectionProgress(ladder, if (step == null) 0 else ladder.indexOf(step), failed = false)
}
