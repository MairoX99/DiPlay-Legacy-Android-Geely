package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticRedactorTest {
    @Test fun savedDriveReportKeepsMediaPerformanceCounters() {
        val folder = Files.createTempDirectory("diplay-media-report").toFile()
        try {
            val audio = "audio stats audioType=media codec=AAC_LC rx=215 dropped=0 underruns=+3 queue=2 playing=true maxGapMs=420 sinceRxMs=10 maxWriteMs=22 decoderDroppedTotal=0 outputBuffersTotal=212 ended=true"
            val video = "Video: video stats rx=29.8fps shown=29.8fps maxGap=150ms kbps=4000 recoveries=0 touch2frame avg=85ms max=110ms n=3 touchSendMax=1ms"
            SessionLogFile(folder.resolve("diplay.log")).use {
                it.reset("started")
                it.append(audio)
                it.append(video)
            }
            val report = folder.resolve("diplay.log").readText()
            assertTrue(report.contains(audio))
            assertTrue(report.contains(video))
        } finally { folder.deleteRecursively() }
    }
    /** Protocol payloads and multi-line text are not evidence; they are dropped outright. */
    @Test fun protocolPayloadsAndMultilineTextAreDroppedOutright() {
        for (line in listOf("TRACE IAP2 tx key", "PHONE rx", "ok\nsecret")) {
            assertNull(line, DiagnosticRedactor.redact(line))
        }
    }

    /**
     * A line that carries a credential says so instead of vanishing. Returning null made a withheld
     * line and an absent one the same thing, so "the certificate was rejected" — a sentence about the
     * failure, with no value in it — was dropped for the keyword alone and the report read as if the
     * step had never run.
     */
    @Test fun aCredentialLineIsFlaggedNotSilentlyVanished() {
        for (line in listOf("hotspot passphrase=secret", "token=secret", "certificate bytes=607", "rx body={phone: 'Jane'}", "wifi ssid=Home", "wireless name=Jane Smith's iPhone")) {
            val redacted = DiagnosticRedactor.redact(line)
            assertNotNull(line, redacted)
            assertEquals(line, DiagnosticRedactor.WITHHELD, redacted)
            assertFalse(line, redacted!!.contains("secret"))
            assertFalse(line, redacted.contains("Jane"))
        }
    }

    /** `filename:` and `username:` are not device names, and must not cost the line. */
    @Test fun aNameThatIsNotADeviceNameSurvives() {
        for (line in listOf("usb/list filename: cable.txt", "settings username: driver")) {
            assertEquals(line, line, DiagnosticRedactor.redact(line))
        }
    }
    /**
     * A line naming the pair record is evidence; a line carrying one is a credential. The filter used
     * to drop both, so the report lost the single line that says whether the saved Lockdown record was
     * reused or a new one created — the one fact that tells a stale host apart from a phone that has
     * forgotten this accessory, which is what a rejected HostID looks like from here.
     */
    @Test fun namingThePairRecordSurvivesWhileItsValueDoesNot() {
        assertEquals(
            "wired using saved Lockdown pair record",
            DiagnosticRedactor.redact("wired using saved Lockdown pair record"),
        )
        assertEquals(
            "wired created a new Lockdown pair record",
            DiagnosticRedactor.redact("wired created a new Lockdown pair record"),
        )
        // The value still never leaves; the contract changed from "dropped" to "flagged", so these
        // are the placeholder now, and the placeholder must not carry the value it replaced.
        for (line in listOf("pair record=deadbeef", "pair_record: deadbeef")) {
            assertEquals(line, DiagnosticRedactor.WITHHELD, DiagnosticRedactor.redact(line))
            assertFalse(line, DiagnosticRedactor.redact(line)!!.contains("deadbeef"))
        }
    }
    @Test fun stateTransitionsSurviveWithoutAddressesOrIdentifiers() {        val line = DiagnosticRedactor.redact("connected peer=C0:A6:00:29:58:0A ip=192.168.31.71 id=0123456789abcdef0123456789abcdef ipv6=fe80::1234:5678:abcd:9%p2p0")!!
        assertTrue(line.contains("connected"))
        assertFalse(line.contains("C0:A6")); assertFalse(line.contains("192.168")); assertFalse(line.contains("012345")); assertFalse(line.contains("fe80"))
    }
    @Test fun logRotationIsBoundedAndRedactionHappensBeforeDisk() {
        val folder = Files.createTempDirectory("diplay-log-test").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("started")
            log.append("password=secret")
            repeat(1600) { log.append("connection state " + "x".repeat(690)) }
            log.append("CarPlay connected")
            log.close()
            assertTrue(folder.resolve("diplay.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("previous.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("diplay.log").readText().contains("CarPlay connected"))
            assertFalse(folder.listFiles()!!.any { it.readText().contains("secret") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun failuresSurviveLaterSuccessfulSessionsAndOldestHistoryExpires() {
        val folder = Files.createTempDirectory("diplay-history-test").toFile()
        try {
            repeat(10) { session ->
                SessionLogFile(folder.resolve("diplay.log")).use {
                    it.reset("session=$session")
                    it.append(if (session == 3) "Wi-Fi P2P create rejected code=0" else "CarPlay connected")
                    it.append("password=secret")
                }
            }
            val history = SessionLogFile.REPORT_NAMES.map { folder.resolve(it).readText() }
            assertEquals(8, folder.listFiles()!!.size)
            assertTrue(history.first().contains("session=2"))
            assertTrue(history.last().contains("session=9"))
            assertTrue(history.any { it.contains("rejected code=0") })
            assertFalse(history.any { it.contains("secret") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun safeWifiMetadataSurvivesWithoutWeakeningCredentialFilters() {
        val lines = listOf(
            "Wi-Fi P2P preflight wifiEnabled=true locationEnabled=false permissionGranted=true stationMHz=5180",
            "Wi-Fi P2P create mode=FIXED_2_GHZ frequencyMHz=2437",
            "Wi-Fi P2P create rejected code=0 reason=generic error",
            "Wi-Fi P2P ready mode=FIXED_2_GHZ band=2.4 GHz channel=6 frequencyMHz=2437",
            "Wi-Fi P2P channel requestedMHz=2437 actualMHz=2412 matched=false",
            "wireless hotspot backend=Wi-Fi P2P iface=p2p0 host=192.168.49.1 band=5 GHz channel=36 frequency=5180MHz",
        )
        for (line in lines) assertNotNull(line, DiagnosticRedactor.redact(line))
        assertFalse(DiagnosticRedactor.redact(lines.last())!!.contains("192.168.49.1"))
    }

    /**
     * An activity can be recreated while the previous session's writer is still draining, which
     * puts two SessionLogFile instances on one path. The newcomer truncates the file; the old
     * writer's next append must not read its own stale size and rotate that fresh file away.
     */
    @Test fun aNewInstancesTruncateSurvivesTheOldWritersNextAppend() {
        val folder = Files.createTempDirectory("diplay-log-shared-state").toFile()
        try {
            val path = folder.resolve("diplay.log")
            val previous = SessionLogFile(path)
            previous.reset("session=previous")
            val filler = "connection state " + "x".repeat(690)
            var appends = 0
            while (path.length() <= SessionLogFile.MAX_BYTES && appends < 3000) {
                previous.append(filler)
                appends++
            }
            assertTrue("fixture must leave the previous writer above MAX_BYTES", path.length() > SessionLogFile.MAX_BYTES)

            SessionLogFile(path).use { it.reset("session=new") }
            previous.append("drained line from the previous session")
            previous.close()

            val report = path.readText()
            assertTrue("the previous writer rotated the new session's log away", report.contains("session=new"))
            assertTrue(report.contains("drained line from the previous session"))
        } finally { folder.deleteRecursively() }
    }

    /**
     * The accessory's SDP answer is only as good as knowing what was asked: a phone that wanted an attribute
     * the published record does not carry and a phone that never got an answer at all look identical from this
     * side, so the request's parameters go into the log. They go in with the octets separated, because an
     * unbroken run of 24 or more hex digits reads to this filter as an opaque identifier and is replaced —
     * which would leave the line saying nothing about what was asked.
     */
    @Test fun aSpacedHexQuestionSurvivesWhileAnUnbrokenBlobWouldNot() {
        val question = "adapter-bt: sdp asked (pattern max ids cont) 35 03 19 01 00 09 00 04"
        assertEquals(question, DiagnosticRedactor.redact(question))
        assertEquals(
            "sdp asked [identifier]",
            DiagnosticRedactor.redact("sdp asked 3503190100090004350519000308"),
        )
    }
}
