package com.shilapi.xcertplay.transport.hci

/**
 * The values this stack puts on the air, for whichever USB Bluetooth radio is plugged in.
 *
 * The name is ours; the class of device, the scan setting and the service UUID are the Bluetooth standard's.
 * No model numbers live here: a radio is recognised by the interface class it announces or by the endpoints it
 * exposes, so an adapter nobody has seen works the same way. Plugging one in does not launch the app.
 */
object ActionsBluetooth {
    /** The one place the advertised name is written. The connection page reads it from here. */
    const val BROADCAST_NAME = "Xingrui CarPlay"

    /** Audio/Video major class; iOS shows the device regardless, so this only has to be sane. */
    const val CLASS_OF_DEVICE = 0x200420
    const val SCAN_ENABLE_DISCOVERABLE_CONNECTABLE = 0x03
    const val IAP2_UUID_128 = "00000000-deca-fade-deca-deafdecacafe"

    /**
     * Wireless CarPlay service class UUID, little-endian, for the extended inquiry response.
     * Apple Accessory Interface Specification, CarPlay, the 128-bit UUID an iPhone looks for
     * while scanning. This is not the iAP2 SDP UUID, and it is not written in SDP byte order.
     */
    val CARPLAY_EIR_UUID_128: ByteArray = byteArrayOf(
        0xD3.toByte(), 0x1F, 0xBF.toByte(), 0x50,
        0x5D, 0x57, 0x27, 0x97.toByte(),
        0xA2.toByte(), 0x40, 0x41, 0xCD.toByte(),
        0x48, 0x43, 0x88.toByte(), 0xEC.toByte(),
    )

    /**
     * The iAP2 service class UUID of an **accessory**, little-endian, for the extended inquiry response.
     *
     * Apple's Bluetooth Accessories spec requires the accessory's iAP2 ServiceClassUUID in the EIR as well as
     * in SDP, and spells it `0xFFCACADEAFDECADEDEFACADE00000000` — that is, the canonical
     * `00000000-deca-fade-deca-deafdecacaff` byte-reversed, an EIR's 128-bit UUID list being little-endian.
     *
     * It is not [IAP2_UUID_128]: that one names the *phone's* side of the service and differs in its last
     * octet, which is why the two cannot be substituted for each other. Declaring the phone's UUID here would
     * say this radio is an Apple device.
     */
    val IAP2_EIR_UUID_128: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xCA.toByte(), 0xCA.toByte(), 0xDE.toByte(),
        0xAF.toByte(), 0xDE.toByte(), 0xCA.toByte(), 0xDE.toByte(),
        0xDE.toByte(), 0xFA.toByte(), 0xCA.toByte(), 0xDE.toByte(),
        0x00, 0x00, 0x00, 0x00,
    )
}
