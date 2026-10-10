package com.shilapi.xcertplay.transport

/**
 * USB-adapter hop.
 *
 * Off for shipping builds: this fork's wireless path is the car's own Bluetooth, and a driver is not
 * asked to buy an adapter for it. The stack behind the hop is kept whole — setting this back to true
 * restores the USB claim, the radio choice and the adapter's step line together.
 */
object ExternalBluetoothRoute {
    const val enabled = false
}
