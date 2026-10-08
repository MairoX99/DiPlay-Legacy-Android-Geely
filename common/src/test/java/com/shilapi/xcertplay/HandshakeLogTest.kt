package com.shilapi.xcertplay

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

    @Test fun playbackChatterAndSecretsStayOffTheConnectionScreen() {
        assertNull(HandshakeLog.displayLine("wired iap2 tx=0xfffb location-information"))
        assertNull(HandshakeLog.displayLine("iap2 tx=0xa101 vehicle-status range=1km"))
        assertNull(HandshakeLog.displayLine("TRACE IAP2 tx"))
        assertNull(HandshakeLog.displayLine("PHONE accessoryd hello"))
        assertNull(HandshakeLog.displayLine("hotspot passphrase=secret"))
        assertNull(HandshakeLog.displayLine("cluster map shown"))
        assertNull(HandshakeLog.displayLine("正在查找 iPhone"))
    }
}
