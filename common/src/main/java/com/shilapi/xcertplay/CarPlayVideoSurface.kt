package com.shilapi.xcertplay

internal enum class CarPlayVideoSurfaceMode { TEXTURE, SURFACE }

/** Android 6 (API 23) and older composite a TextureView through an extra GPU texture. */
internal fun directSurfaceViewDefault(sdkInt: Int): Boolean = sdkInt <= 23

internal fun carPlayVideoSurfaceMode(directSurfaceView: Boolean): CarPlayVideoSurfaceMode =
    if (directSurfaceView) CarPlayVideoSurfaceMode.SURFACE else CarPlayVideoSurfaceMode.TEXTURE
