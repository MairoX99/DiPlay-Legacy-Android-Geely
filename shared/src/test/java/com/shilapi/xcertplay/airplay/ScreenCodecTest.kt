package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test
    fun decryptFrameKeepsANewArrayWhenTheReceiveBufferIsReused() {
        val key = ByteArray(32) { index -> (index + 4).toByte() }
        val header = ByteArray(128)
        val cipher = AirPlayAead(forEncryption = false)
        val nonce = ByteArray(12)
        val body = ByteArray(64)
        val first = decrypt(key, header, cipher, nonce, body, 0L, byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55))
        val secondPlain = byteArrayOf(0x66, 0x77)
        val second = decrypt(key, header, cipher, nonce, body, 1L, secondPlain)

        body.fill(0x5a)
        assertNotSame(body, first)
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55), first)
        assertArrayEquals(secondPlain, second)
    }

    private fun decrypt(
        key: ByteArray,
        header: ByteArray,
        cipher: AirPlayAead,
        nonce: ByteArray,
        body: ByteArray,
        counter: Long,
        plain: ByteArray,
    ): ByteArray {
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header)
        sealed.copyInto(body)
        return ScreenCodec.decryptFrame(key, counter, header, body, sealed.size, cipher, nonce)
    }
}
