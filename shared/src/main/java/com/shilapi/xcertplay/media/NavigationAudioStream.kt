package com.shilapi.xcertplay.media

import android.os.Build

/**
 * The head unit stream that carries spoken navigation guidance.
 *
 * Index 14 is BYD's driver-speaker stream. Geely's E01 (FS11GQJ) renumbers the vendor streams, so
 * there 14 is STREAM_FM (the FM tuner) and guidance belongs on STREAM_NAVI_TTS at 10 — its own
 * `android.media.AudioManager` declares `STREAM_NAVI_TTS = 10`. One hardcoded value cannot serve
 * both, so the default follows the head unit.
 */
object NavigationAudioStream {
    const val BYD_DRIVER_SPEAKER = 14
    const val E01_NAVI_TTS = 10

    /** Device-appropriate default; read per call so it never outlives a head-unit change. */
    val deviceDefault: Int
        get() = if (Build.DEVICE.equals("FS11GQJ", ignoreCase = true)) E01_NAVI_TTS else BYD_DRIVER_SPEAKER
}
