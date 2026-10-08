package com.shilapi.xcertplay

/** One on-screen line for a USB host device. The bus path is the last two name segments. */
internal object UsbBusFormat {
    fun line(
        deviceName: String,
        vendorId: Int,
        productId: Int,
        deviceClass: Int,
        deviceSubclass: Int,
        deviceProtocol: Int,
        configs: String,
        productLabel: String?,
    ): String {
        val segments = deviceName.trim().trim('/').split('/').filter { it.isNotEmpty() }
        val where = if (segments.size >= 2) segments.takeLast(2).joinToString("/") else deviceName.ifBlank { "?" }
        val label = productLabel?.trim()?.replace('\n', ' ')?.replace('\r', ' ')?.take(32).orEmpty()
        val suffix = if (label.isEmpty()) "" else "  $label"
        val shownConfigs = configs.ifBlank { "none" }
        return "$where  ${vendorId.toString(16).padStart(4, '0')}:${productId.toString(16).padStart(4, '0')}  " +
            "$deviceClass/$deviceSubclass/$deviceProtocol  cfg $shownConfigs$suffix"
    }
}
