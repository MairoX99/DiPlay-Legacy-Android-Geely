package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFocusGainTest {
    @Test fun transientLossMutesAndGainRestores() {
        assertEquals(0f, mediaTrackGainForFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(1f, mediaTrackGainForFocus(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test fun duckLowersMediaWithoutMutingIt() {
        assertEquals(0.2f, mediaTrackGainForFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
    }

    @Test fun permanentLossDoesNotChangeTheTrack() {
        assertNull(mediaTrackGainForFocus(AudioManager.AUDIOFOCUS_LOSS))
        assertNull(mediaTrackGainForFocus(0))
    }
}
