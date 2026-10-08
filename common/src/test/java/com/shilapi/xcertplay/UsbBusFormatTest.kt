package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbBusFormatTest {
    @Test fun keepsBusPathVendorAndConfiguration() {
        assertEquals(
            "001/004  05ac:12a8  0/0/0  cfg 1:3  iPhone",
            UsbBusFormat.line("/dev/bus/usb/001/004", 0x05ac, 0x12a8, 0, 0, 0, "1:3", "iPhone"),
        )
    }

    @Test fun blankConfigurationAndLabelAreOmitted() {
        assertEquals(
            "001/002  1d6b:0002  9/0/1  cfg none",
            UsbBusFormat.line("/dev/bus/usb/001/002", 0x1d6b, 0x0002, 9, 0, 1, "", "  "),
        )
    }
}
