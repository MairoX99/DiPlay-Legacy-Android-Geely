package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.AdapterBringUp
import com.shilapi.xcertplay.transport.AdapterBringUpStep
import com.shilapi.xcertplay.transport.hci.ActionsBluetooth

/** Plain sentence for the step the external adapter is on, including what to do next. */
internal fun adapterBringUpText(context: Context, report: AdapterBringUp): String {
    val name = ActionsBluetooth.BROADCAST_NAME
    return when (report.step) {
        AdapterBringUpStep.NOT_PLUGGED -> context.getString(R.string.adapter_step_not_plugged)
        AdapterBringUpStep.USB_PERMISSION -> context.getString(R.string.adapter_step_usb_permission)
        AdapterBringUpStep.OPEN_DEVICE -> context.getString(R.string.adapter_step_open_device)
        AdapterBringUpStep.CLAIM_INTERFACE -> context.getString(R.string.adapter_step_claim_interface)
        AdapterBringUpStep.NO_ENDPOINTS -> context.getString(R.string.adapter_step_no_endpoints)
        AdapterBringUpStep.NOT_STARTED -> context.getString(R.string.adapter_step_not_started)
        AdapterBringUpStep.RESETTING -> context.getString(R.string.adapter_step_resetting)
        AdapterBringUpStep.ADVERTISE -> context.getString(R.string.adapter_step_advertise, name)
        AdapterBringUpStep.HOTSPOT_OFF -> context.getString(R.string.adapter_step_hotspot_off)
        AdapterBringUpStep.WAITING_FOR_PAIR -> context.getString(R.string.adapter_step_waiting_for_pair, name)
        AdapterBringUpStep.PAIRING -> context.getString(R.string.adapter_step_pairing)
        AdapterBringUpStep.PAIRING_STALLED -> context.getString(R.string.adapter_step_pairing_stalled, name)
        AdapterBringUpStep.NOT_PAIRED -> context.getString(R.string.adapter_step_not_paired)
        AdapterBringUpStep.ACL_CONNECT -> context.getString(R.string.adapter_step_acl_connect, name)
        AdapterBringUpStep.ACL_AUTHENTICATION -> context.getString(R.string.adapter_step_acl_authentication, name)
        AdapterBringUpStep.SDP -> context.getString(R.string.adapter_step_sdp, name)
        AdapterBringUpStep.RFCOMM -> context.getString(R.string.adapter_step_rfcomm, name)
        AdapterBringUpStep.READY -> context.getString(R.string.adapter_step_ready)
        AdapterBringUpStep.PAIRED -> context.getString(R.string.adapter_step_paired)
        AdapterBringUpStep.FAILED -> context.getString(R.string.adapter_step_failed, report.reason ?: "unknown")
    }
}
