package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.WiredLinkAssessment
import com.shilapi.xcertplay.transport.WiredLinkObservation
import com.shilapi.xcertplay.transport.WiredLinkStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandshakeLogTest {
    @Test fun stepAndVendorFailureStayVisible() {
        assertEquals(
            "STEP usb/reenum: waiting for the CarPlay USB configuration",
            HandshakeLog.displayLine("STEP usb/reenum: waiting for the CarPlay USB configuration"),
        )
        assertEquals(
            "ERROR usb/reenum vendor IN transferred -1 of 1 bytes",
            HandshakeLog.displayLine("ERROR usb/reenum vendor IN transferred -1 of 1 bytes"),
        )
        assertEquals(
            "失败：usb/reenum vendor IN transferred -1 of 1 bytes",
            HandshakeLog.displayLine("失败：usb/reenum vendor IN transferred -1 of 1 bytes"),
        )
    }

    @Test fun certificateStepIsShownWithoutTheSecretWord() {
        assertEquals(
            "wired iap2 mfi rx=0xaa00 request-cert",
            HandshakeLog.displayLine("wired iap2 mfi rx=0xaa00 request-certificate"),
        )
    }

    @Test fun anAdoptedCarHotspotIsNamedOnTheScreen() {
        // Written while the settings load, before anything else has logged, so this allowlist is the
        // only thing standing between the driver and an unexplained hotspot name they never typed.
        assertEquals(
            "STEP wifi/ap: manual hotspot credentials taken from the car's own hotspot",
            HandshakeLog.displayLine(
                "STEP wifi/ap: manual hotspot credentials taken from the car's own hotspot",
            ),
        )
    }

    @Test fun playbackChatterAndSecretsStayOffTheConnectionScreen() {
        assertNull(HandshakeLog.displayLine("wired iap2 tx=0xfffb location-information"))
        assertNull(HandshakeLog.displayLine("iap2 tx=0xa101 vehicle-status range=1km"))
        assertNull(HandshakeLog.displayLine("TRACE IAP2 tx"))
        assertNull(HandshakeLog.displayLine("PHONE accessoryd hello"))
        assertNull(HandshakeLog.displayLine("hotspot passphrase=secret"))
        assertNull(HandshakeLog.displayLine("cluster map shown"))
        assertNull(HandshakeLog.displayLine("正在查找 iPhone"))
    }

    /**
     * The wired-link diagnosis has to reach the connection screen, or it is not a diagnosis. It goes
     * out as a debug line rather than a status — a status is deduplicated on equality, which would
     * either swallow it or restate it on every poll — so this promotion is the only thing carrying it
     * there. It is asserted against the real report so the two cannot drift apart.
     */
    @Test fun theWiredLinkDiagnosisReachesTheConnectionScreen() {
        val report = WiredLinkAssessment.assess(
            WiredLinkObservation(
                wired = true,
                step = WiredLinkStep.SEARCHING,
                emptyPolls = WiredLinkAssessment.STUCK_POLLS,
                deviceCount = 0,
                detachCycles = 0,
                sessionRan = false,
            ),
        )

        assertEquals(report.logLine(), HandshakeLog.displayLine(report.logLine()))
    }
}
