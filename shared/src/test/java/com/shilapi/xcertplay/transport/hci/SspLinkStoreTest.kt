package com.shilapi.xcertplay.transport.hci

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SspLinkStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun store() = SspLinkStore(File(folder.root, "linkkeys.tsv"))

    @Test fun storesAndReadsBackAKeyForItsOwnAddressOnly() {
        val store = store()
        val key = ByteArray(16) { (it + 1).toByte() }
        store.put("CC:60:23:D7:2F:5B", key, 5)

        assertArrayEquals(key, store.key("cc:60:23:d7:2f:5b")!!.linkKey)
        assertNull(store.key("F4:4E:FC:F8:8B:36"))
    }

    @Test fun survivesAProcessRestart() {
        store().put("CC:60:23:D7:2F:5B", ByteArray(16) { 0x7F }, 5)
        assertEquals(listOf("CC:60:23:D7:2F:5B"), store().addresses())
    }

    @Test fun replacesAnExistingKeyForTheSameAddress() {
        val store = store()
        store.put("CC:60:23:D7:2F:5B", ByteArray(16) { 1 }, 5)
        store.put("CC:60:23:D7:2F:5B", ByteArray(16) { 2 }, 5)

        assertEquals(1, store.addresses().size)
        assertArrayEquals(ByteArray(16) { 2 }, store.key("CC:60:23:D7:2F:5B")!!.linkKey)
    }

    /** Real link keys are arbitrary bits; a sign-extended hex conversion would lose the high ones. */
    @Test fun aKeyWithHighBytesSurvivesTheRoundTrip() {
        val store = store()
        val key = byteArrayOf(
            0xF4.toByte(), 0x4E, 0xFC.toByte(), 0xF8.toByte(), 0x8B.toByte(), 0x36,
            0x80.toByte(), 0xFF.toByte(), 0x00, 0x7F, 0xAA.toByte(), 0x55,
            0x01, 0xFE.toByte(), 0x90.toByte(), 0x0D,
        )
        store.put("CC:60:23:D7:2F:5B", key, 5)

        assertArrayEquals(key, SspLinkStore(File(folder.root, "linkkeys.tsv")).key("CC:60:23:D7:2F:5B")!!.linkKey)
    }

    @Test fun aCorruptLineIsIgnoredRatherThanFatal() {        val file = File(folder.root, "linkkeys.tsv")
        file.writeText("not-a-key-line\nDE:AD:BE:EF:00:01\t5\t" + "11".repeat(16) + "\n")
        val store = SspLinkStore(file)

        assertEquals(listOf("DE:AD:BE:EF:00:01"), store.addresses())
    }
}
