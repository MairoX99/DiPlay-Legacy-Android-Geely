package com.shilapi.xcertplay

/**
 * Lines worth showing on the connection screen while a phone handshake is in progress.
 * Localized "正在…" status stays on the stage line. Location and vehicle-status updates
 * continue after the session is up and are left out.
 */
internal object HandshakeLog {
    const val MAX_LINES = 24

    fun displayLine(message: String): String? {
        val line = message.trim()
        if (line.isEmpty() || line.contains('\n') || line.contains('\r')) return null
        if (line.contains("TRACE ") || line.contains("PHONE ")) return null
        if (line.contains("location-information") || line.contains("vehicle-status")) return null
        if (line.contains("passphrase", true) || line.contains("password", true) || line.contains("token", true)) {
            return null
        }
        val safe = line.replace(Regex("(?i)certificate"), "cert")
        val interesting = safe.startsWith("STEP ") ||
            safe.startsWith("ERROR ") ||
            safe.contains("failed", true) ||
            safe.contains("denied", true) ||
            safe.contains("transferred", true) ||
            safe.contains("retry", true) ||
            safe.contains("wired ") ||
            safe.contains("iap2 ") ||
            safe.contains("usb/") ||
            safe.contains("ncm ") ||
            safe.contains("MFi", true) ||
            safe.contains("失败")
        return if (interesting) safe.take(400) else null
    }
}
