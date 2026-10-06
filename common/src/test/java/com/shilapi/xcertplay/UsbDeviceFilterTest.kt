package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * `usb_device_filter.xml` is what makes Android launch DiPlay when a device is plugged in, and it
 * has to agree with the vendor ids the transport code looks for. The two are separate files with no
 * compiler between them, so the agreement is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbDeviceFilterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun appleVendorIsMatchedSoAnIphoneStartsTheApp() {
        assertTrue(
            "usb_device_filter.xml must list Apple's vendor id (${IphoneUsbMatcher.APPLE_VENDOR_ID}), " +
                "or plugging in an iPhone does not launch DiPlay",
            IphoneUsbMatcher.APPLE_VENDOR_ID in filteredVendorIds(),
        )
    }

    @Test
    fun theCh341BridgeIsStillMatched() {
        assertTrue(
            "the CH341 entry drives the I2C bridge and must not be dropped",
            CH341_VENDOR_ID in filteredVendorIds(),
        )
    }

    @Test
    fun everyFilterEntryNamesAVendor() {
        val vendorIds = filteredVendorIds()
        assertTrue("usb_device_filter.xml declares no <usb-device> entries", vendorIds.isNotEmpty())
        assertTrue(
            "an empty vendor-id means the entry matches every device, which is not intended here",
            vendorIds.none { it == 0 },
        )
    }

    private fun filteredVendorIds(): List<Int> {
        val parser = context.resources.getXml(R.xml.usb_device_filter)
        val ids = mutableListOf<Int>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "usb-device") {
                parser.getAttributeValue(null, "vendor-id")?.trim()?.toIntOrNull()?.let(ids::add)
            }
            event = parser.next()
        }
        return ids
    }

    private companion object {
        /** USB\VID_1A86 — the CH341 in the wired I2C bridge. */
        const val CH341_VENDOR_ID = 6790
    }
}
