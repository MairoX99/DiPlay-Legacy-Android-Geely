package com.shilapi.xcertplay.transport.hci

/** How HCI commands reach a radio: the standard bulk pair, or a vendor control request. */
enum class UsbHciCommandPath { CONTROL_REQUEST, BULK_OUT }

internal object UsbCommandPaths {
    /**
     * The path to try first, then the other one.
     *
     * The control request goes first because the Bluetooth spec puts HCI commands on the default control pipe
     * (Vol 4, Part B), and because its failure is visible: a radio that does not implement the request stalls
     * the transfer and reports -1, so the bulk pair is still reachable behind it. The reverse order has no such
     * escape — writing an HCI command to a bulk OUT endpoint that carries ACL completes successfully whether or
     * not the radio ever acted on the bytes, so a radio that wanted the control request would be left talking to
     * an endpoint nobody reads and no later command would ever be tried the other way.
     *
     * Whichever path the write did not fail on is remembered, so the common case costs one transfer.
     */
    fun order(preferred: UsbHciCommandPath, working: UsbHciCommandPath?): List<UsbHciCommandPath> {
        working?.let { return listOf(it) }
        return listOf(preferred, other(preferred))
    }

    fun other(path: UsbHciCommandPath): UsbHciCommandPath = when (path) {
        UsbHciCommandPath.CONTROL_REQUEST -> UsbHciCommandPath.BULK_OUT
        UsbHciCommandPath.BULK_OUT -> UsbHciCommandPath.CONTROL_REQUEST
    }
}
