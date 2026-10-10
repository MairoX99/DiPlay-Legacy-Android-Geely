package com.shilapi.xcertplay.transport.hci

import java.io.File
import java.util.Locale

internal data class StoredLinkKey(val address: String, val linkKey: ByteArray, val keyType: Int)

/**
 * Link keys the adapter's host asked us to remember, keyed strictly by the peer address.
 *
 * A wrong key paired with the right address makes the phone reject authentication; keeping the
 * address uppercased is what makes the lookup order-insensitive without ever matching a different
 * device.
 */
internal class SspLinkStore(private val file: File) {
    fun key(address: String): StoredLinkKey? = read().firstOrNull { it.address == normalise(address) }

    /** The stored addresses in the order they were written, oldest first. */
    fun addresses(): List<String> = read().map { it.address }

    @Synchronized
    fun put(address: String, linkKey: ByteArray, keyType: Int) {
        require(linkKey.size == 16) { "a link key is 16 bytes" }
        val normalised = normalise(address)
        write(
            read().filterNot { it.address == normalised } +
                StoredLinkKey(normalised, linkKey, keyType),
        )
    }

    /**
     * Drops the key for [address], so the next connection pairs from scratch.
     *
     * A phone that has forgotten the pairing answers authentication with HCI error 0x06, PIN or Key Missing.
     * A link key is a shared secret and one side cannot repair it alone, so keeping our half only makes every
     * later attempt fail the same way.
     */
    @Synchronized
    fun forget(address: String) {
        write(read().filterNot { it.address == normalise(address) })
    }

    private fun write(lines: List<StoredLinkKey>) {
        file.parentFile?.mkdirs()
        file.writeText(
            lines.joinToString("") { line ->
                line.address + "\t" + line.keyType + "\t" + line.linkKey.toHex() + "\n"
            },
        )
    }

    private fun read(): List<StoredLinkKey> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().mapNotNull(::parse).toList()
        }.getOrDefault(emptyList())
    }

    private fun parse(line: String): StoredLinkKey? {
        val parts = line.trim().split('\t')
        if (parts.size != 3) return null
        val type = parts[1].toIntOrNull() ?: return null
        val key = parts[2].hexToBytesOrNull() ?: return null
        if (key.size != 16 || parts[0].length != 17) return null
        return StoredLinkKey(normalise(parts[0]), key, type)
    }

    private fun normalise(address: String): String = address.uppercase(Locale.US)

    private fun ByteArray.toHex(): String =
        joinToString("") { String.format(Locale.US, "%02X", it) }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
