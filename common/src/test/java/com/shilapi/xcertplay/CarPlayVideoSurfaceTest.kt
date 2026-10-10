package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVideoSurfaceTest {
    @Test
    fun androidSixAndOlderDrawStraightToTheScreen() {
        assertTrue(directSurfaceViewDefault(19))
        assertTrue(directSurfaceViewDefault(22))
        assertTrue(directSurfaceViewDefault(23))
        assertFalse(directSurfaceViewDefault(24))
    }

    @Test
    fun theSwitchChoosesTheVideoView() {
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(true))
        assertEquals(CarPlayVideoSurfaceMode.TEXTURE, carPlayVideoSurfaceMode(false))
    }
}
