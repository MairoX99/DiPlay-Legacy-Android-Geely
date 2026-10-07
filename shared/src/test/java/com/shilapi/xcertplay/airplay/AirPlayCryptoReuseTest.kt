package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AirPlayCryptoReuseTest {
    private val key = ByteArray(32) { index -> (index + 1).toByte() }
    private val aad = "screen-header".toByteArray()

    @Test
    fun openIntoMatchesAllocatingOpen() {
        val nonce = AirPlayCrypto.nonce64(42)
        val plain = "plaintext-frame".toByteArray()
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        val carrier = ByteArray(7 + sealed.size + 3)
        sealed.copyInto(carrier, 7)
        val output = ByteArray(4 + plain.size + 4)
        val cipher = AirPlayAead(forEncryption = false)

        val written = cipher.process(key, nonce, carrier, 7, sealed.size, aad, output, 4)

        assertEquals(plain.size, written)
        assertArrayEquals(plain, output.copyOfRange(4, 4 + written))
        assertArrayEquals(plain, AirPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    @Test
    fun sealIntoMatchesAllocatingSeal() {
        val nonce = AirPlayCrypto.nonce64(7)
        val plain = "mic-or-video".toByteArray()
        val output = ByteArray(plain.size + 16)
        val sealer = AirPlayAead(forEncryption = true)

        val written = sealer.process(key, nonce, plain, 0, plain.size, aad, output, 0)

        assertArrayEquals(AirPlayCrypto.chachaSeal(key, nonce, plain, aad), output.copyOf(written))
    }

    @Test
    fun reusedCipherOpensTwoMessagesAfterAuthFailure() {
        val cipher = AirPlayAead(forEncryption = false)
        val first = roundTrip(cipher, 1L, "first-payload")
        val badNonce = AirPlayCrypto.nonce64(99)
        val bad = AirPlayCrypto.chachaSeal(key, badNonce, "nope".toByteArray(), aad)
        try {
            cipher.process(key, AirPlayCrypto.nonce64(2), bad, 0, bad.size, aad, ByteArray(bad.size), 0)
            fail("tampered ciphertext was accepted")
        } catch (_: Exception) {
            // The next init has to clear this failure.
        }
        val second = roundTrip(cipher, 2L, "second-payload")

        assertArrayEquals("first-payload".toByteArray(), first)
        assertArrayEquals("second-payload".toByteArray(), second)
    }

    private fun roundTrip(cipher: AirPlayAead, counter: Long, text: String): ByteArray {
        val plain = text.toByteArray()
        val nonce = AirPlayCrypto.nonce64(counter)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
        val output = ByteArray(plain.size)
        val written = cipher.process(key, nonce, sealed, 0, sealed.size, aad, output, 0)
        assertEquals(plain.size, written)
        return output.copyOf(written)
    }
}
