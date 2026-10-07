package com.shilapi.xcertplay.media

import android.media.AudioManager

/**
 * Relative gain for music tracks when Android reports a focus change.
 *
 * Transient loss mutes media so a phone call can use the head unit's audio path.
 * Ducking lowers it. A later gain restores full volume. Permanent loss is ignored:
 * some head units take focus and never send gain back, and muting then would
 * leave CarPlay silent. Telephony, navigation and assistant tracks are not
 * affected; callers apply this only to `audioType == "media"`.
 *
 * Uses the API 8 focus constants. This fork does not use [android.media.AudioFocusRequest],
 * which starts at API 26.
 */
internal fun mediaTrackGainForFocus(change: Int): Float? = when (change) {
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MUTED
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> DUCKED
    AudioManager.AUDIOFOCUS_GAIN -> FULL
    else -> null
}

private const val FULL = 1f
private const val DUCKED = 0.2f
private const val MUTED = 0f
