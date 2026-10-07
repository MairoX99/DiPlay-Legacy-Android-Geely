package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PlaybackBufferReuseTest {
    @Test
    fun audioCaptureCopiesBytesBeforeReturning() {
        val folder = Files.createTempDirectory("audio-capture-reuse").toFile()
        try {
            val capture = AudioPacketCapture(folder, 3)
            val wire = ByteArray(32)
            "wire-bytes-one".toByteArray().copyInto(wire)
            val rtp = ByteArray(24)
            "rtp-bytes-one".toByteArray().copyInto(rtp)
            capture.record(wire, 14, rtp, 5, null)
            wire.fill('Z'.code.toByte())
            rtp.fill('Q'.code.toByte())
            capture.close()

            val stored = capture.file.readBytes()
            assertTrue(stored.containsSlice("wire-bytes-one".toByteArray()))
            assertTrue(stored.containsSlice("rtp-bytes-one".toByteArray()))
            assertFalse(stored.containsSlice("ZZZZ".toByteArray()))
            assertFalse(stored.containsSlice("QQQQ".toByteArray()))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun queuedAudioRtpSurvivesTheNextDatagram() {
        val key = ByteArray(32) { index -> (index + 3).toByte() }
        val stream = AudioStream(key, 7)
        val rtpPackets = Collections.synchronizedList(mutableListOf<ByteArray>())
        val (dataPort, _) = stream.listen(object : AudioStream.Listener {
            override fun onRtp(rtp: ByteArray, sample: Int) {
                rtpPackets.add(rtp)
            }
        })
        val first = "first-audio-payload".toByteArray()
        val second = "second-audio-payload-longer".toByteArray()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline && !holds(rtpPackets, first, second)) {
                sendAudio(dataPort, key, first, sample = 100, nonce = 1)
                sendAudio(dataPort, key, second, sample = 200, nonce = 2)
                Thread.sleep(30)
            }
            assertTrue(holds(rtpPackets, first, second))
            val keptFirst = rtpPackets.first { it.payloadEquals(first) }
            val keptSecond = rtpPackets.first { it.payloadEquals(second) }
            assertNotSame(keptFirst, keptSecond)
            assertArrayEquals(first, keptFirst.copyOfRange(12, keptFirst.size))
            assertArrayEquals(second, keptSecond.copyOfRange(12, keptSecond.size))
        } finally {
            stream.close()
        }
    }

    @Test
    fun queuedVideoFrameSurvivesTheNextShorterFrame() {
        val key = ByteArray(32) { index -> (index + 9).toByte() }
        val frames = Collections.synchronizedList(mutableListOf<ByteArray>())
        val latch = CountDownLatch(2)
        val stream = ScreenStream(key)
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) {
                frames.add(naluBytes)
                latch.countDown()
            }
        })
        val first = ByteArray(40) { 0x11 }
        val second = ByteArray(18) { 0x22 }
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("::1", port), 2_000)
                val output = socket.getOutputStream()
                output.write(videoMessage(key, 0, first))
                output.write(videoMessage(key, 1, second))
                output.flush()
                assertTrue(latch.await(3, TimeUnit.SECONDS))
            }
            assertArrayEquals(first, frames[0])
            assertArrayEquals(second, frames[1])
            assertNotSame(frames[0], frames[1])
        } finally {
            stream.close()
        }
    }

    private fun holds(packets: List<ByteArray>, first: ByteArray, second: ByteArray): Boolean =
        packets.any { it.payloadEquals(first) } && packets.any { it.payloadEquals(second) }

    private fun ByteArray.payloadEquals(payload: ByteArray): Boolean =
        size == 12 + payload.size && copyOfRange(12, size).contentEquals(payload)

    private fun sendAudio(port: Int, key: ByteArray, payload: ByteArray, sample: Int, nonce: Int) {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        writeU32Be(header, 4, sample)
        val nonceBytes = ByteArray(12)
        var value = nonce
        for (index in 4 until 12) {
            nonceBytes[index] = value.toByte()
            value = value ushr 8
        }
        val sealed = AirPlayCrypto.chachaSeal(key, nonceBytes, payload, header.copyOfRange(4, 12))
        val wire = ByteArray(12 + sealed.size + 8)
        header.copyInto(wire)
        sealed.copyInto(wire, 12)
        nonceBytes.copyInto(wire, 12 + sealed.size, 4, 12)
        DatagramSocket().use { socket ->
            socket.send(DatagramPacket(wire, wire.size, InetAddress.getByName("::1"), port))
        }
    }

    private fun videoMessage(key: ByteArray, counter: Long, plain: ByteArray): ByteArray {
        val header = ByteArray(128)
        val sealedSize = plain.size + ScreenCodec.TAG_SIZE
        header[0] = (sealedSize and 0xff).toByte()
        header[1] = (sealedSize ushr 8 and 0xff).toByte()
        header[2] = (sealedSize ushr 16 and 0xff).toByte()
        header[3] = (sealedSize ushr 24 and 0xff).toByte()
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header)
        return header + sealed
    }

    private fun writeU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            if (copyOfRange(start, start + needle.size).contentEquals(needle)) return true
        }
        return false
    }
}
