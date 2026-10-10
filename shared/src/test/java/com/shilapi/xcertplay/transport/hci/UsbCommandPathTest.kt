package com.shilapi.xcertplay.transport.hci

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbCommandPathTest {
    @Test fun thePreferredPathIsTriedFirstWithTheOtherBehindIt() {
        assertEquals(
            listOf(UsbHciCommandPath.CONTROL_REQUEST, UsbHciCommandPath.BULK_OUT),
            UsbCommandPaths.order(UsbHciCommandPath.CONTROL_REQUEST, working = null),
        )
        assertEquals(
            listOf(UsbHciCommandPath.BULK_OUT, UsbHciCommandPath.CONTROL_REQUEST),
            UsbCommandPaths.order(UsbHciCommandPath.BULK_OUT, working = null),
        )
    }

    @Test fun onceAPathHasAnsweredTheOtherIsNotTriedAgain() {
        assertEquals(
            listOf(UsbHciCommandPath.BULK_OUT),
            UsbCommandPaths.order(UsbHciCommandPath.CONTROL_REQUEST, working = UsbHciCommandPath.BULK_OUT),
        )
    }
}
