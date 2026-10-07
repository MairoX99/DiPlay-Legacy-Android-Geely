package com.shilapi.xcertplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RemoteControlClient
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.Collections

/** Steering-wheel media keys with equivalent API 19 and API 21+ backends. */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ECARX_MAX_ATTEMPTS = 6
    private const val ECARX_RETRY_DELAY_MILLIS = 5_000L
    private const val REPEAT_PRESS_MILLIS = 250L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backend: Backend by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) Api21Backend() else LegacyBackend()
    }

    fun attach(context: Context, controller: CarPlayController) {
        backend.attach(context, controller)
        if (wheelController !== controller) startInterception(context, controller)
    }

    fun detach(expected: CarPlayController?) {
        backend.detach(expected)
        if (expected == null || wheelController === expected) {
            stopInterception()
            audioFocusTarget = null
        }
    }

    fun onMediaAudioChanged(active: Boolean) = mainHandler.post { backend.update(active) }

    /** Receives the same focus callbacks as the media-button session. Cleared by [detach]. */
    fun bindAudioFocusTarget(target: ((Int) -> Unit)?) {
        audioFocusTarget = target
    }
    fun onIphonePlaying(playing: Boolean) {
        if (playing) mainHandler.post { backend.regainFocus() }
    }

    internal fun dispatch(event: KeyEvent): Boolean = backend.dispatch(event)

    /**
     * A key that [WheelKeyService]'s filter saw before the head unit did. True means DiPlay took the
     * press, so the filter drops it and the car does not act on it as well.
     */
    fun dispatchFromWheel(event: KeyEvent): Boolean {
        if (wheelController == null) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> when {
                event.repeatCount > 0 -> wheelPressed.contains(event.keyCode)
                isWheelKey(event.keyCode) -> {
                    wheelPressed.add(event.keyCode)
                    sendWheelPress(event.keyCode)
                    true
                }
                else -> false
            }
            KeyEvent.ACTION_UP -> wheelPressed.remove(event.keyCode)
            else -> false
        }
    }

    private fun isWheelKey(keyCode: Int): Boolean =
        CarPlayMediaButton.opensSiri(keyCode) || CarPlayMediaButton.forKeyCode(keyCode) != null

    // A Geely ECARX head unit hands the wheel over through its own input service rather than as a
    // broadcast, and that service is not always up yet when CarPlay attaches. Hence the retries.
    private fun startInterception(context: Context, controller: CarPlayController) {
        releaseInterception()
        wheelController = controller
        if (!EcarxKeyInterceptor.isSupported()) return
        interceptor = EcarxKeyInterceptor(context)
        claimSteeringWheel()
    }

    private fun claimSteeringWheel() {
        val interceptor = interceptor ?: return
        for (keyCodes in ECARX_KEY_GROUPS) {
            val granted = interceptor.start(keyCodes) { keyCode -> sendWheelPress(keyCode) }
            if (granted != null && granted.isNotEmpty()) {
                Log.i(TAG, "steering-wheel keys: ECARX granted ${granted.joinToString()}")
                return
            }
        }
        if (++interceptionAttempts >= ECARX_MAX_ATTEMPTS) {
            Log.i(TAG, "steering-wheel keys: no ECARX grant after $interceptionAttempts attempts")
            return
        }
        mainHandler.postDelayed(retryInterception, ECARX_RETRY_DELAY_MILLIS)
    }

    /**
     * Resolves one wheel press and sends it to CarPlay. The ECARX callback and the accessibility
     * filter both funnel through here, so a head unit that reports the same press on both paths
     * still produces a single CarPlay press.
     */
    private fun sendWheelPress(keyCode: Int): Boolean {
        val controller = wheelController ?: return false
        if (isRepeatPress(keyCode)) return true
        if (CarPlayMediaButton.opensSiri(keyCode)) return controller.requestSiri()
        val index = CarPlayMediaButton.forKeyCode(keyCode) ?: return false
        val sent = controller.sendMediaButton(index)
        Log.i(TAG, "steering-wheel key $keyCode -> CarPlay $index sent=$sent")
        return sent
    }

    /** Both paths can report one physical press; a second report this soon is the same press. */
    private fun isRepeatPress(keyCode: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val repeat = keyCode == lastPressKeyCode && now - lastPressAt < REPEAT_PRESS_MILLIS
        lastPressKeyCode = keyCode
        lastPressAt = now
        return repeat
    }

    private fun stopInterception() {
        releaseInterception()
        wheelController = null
    }

    private fun releaseInterception() {
        mainHandler.removeCallbacks(retryInterception)
        interceptor?.stop()
        interceptor = null
        interceptionAttempts = 0
    }

    /**
     * Worth offering the head unit, most specific first. It grants only the codes it knows, so the
     * first group that comes back non-empty is the one this wheel actually sends.
     */
    private val ECARX_KEY_GROUPS = listOf(
        intArrayOf(
            CarPlayMediaButton.KEYCODE_ECARX_MEDIA_NEXT,
            CarPlayMediaButton.KEYCODE_ECARX_MEDIA_PREVIOUS,
            CarPlayMediaButton.KEYCODE_ECARX_MEDIA_PLAY_PAUSE,
            CarPlayMediaButton.KEYCODE_ECARX_VOICE_ASSIST,
        ),
        intArrayOf(
            CarPlayMediaButton.KEYCODE_ECARX_SEEK_NEXT,
            CarPlayMediaButton.KEYCODE_ECARX_SEEK_PREVIOUS,
            CarPlayMediaButton.KEYCODE_ECARX_R_SEEK_NEXT,
            CarPlayMediaButton.KEYCODE_ECARX_R_SEEK_PREVIOUS,
        ),
        intArrayOf(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ),
    )

    private var interceptor: EcarxKeyInterceptor? = null
    private var wheelController: CarPlayController? = null
    private var interceptionAttempts = 0
    private val wheelPressed = Collections.synchronizedSet(mutableSetOf<Int>())
    private var lastPressKeyCode = Int.MIN_VALUE
    private var lastPressAt = 0L
    @Volatile private var audioFocusTarget: ((Int) -> Unit)? = null
    private val retryInterception = Runnable { claimSteeringWheel() }

    private interface Backend {
        fun attach(context: Context, controller: CarPlayController)
        fun detach(expected: CarPlayController?)
        fun update(active: Boolean)
        fun regainFocus()
        fun dispatch(event: KeyEvent): Boolean
    }

    private abstract class FocusBackend : Backend {
        protected var context: Context? = null
        protected var controller: CarPlayController? = null
        private var focusHeld = false
        private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
            Log.i(TAG, "audio focus change=$change")
            if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
            audioFocusTarget?.invoke(change)
        }

        override fun attach(context: Context, controller: CarPlayController) {
            if (this.controller !== controller) release()
            this.context = context.applicationContext
            this.controller = controller
            controller.playbackListener = ::onPlaybackChanged
        }

        override fun detach(expected: CarPlayController?) {
            if (expected == null || controller !== expected) return
            expected.playbackListener = null
            release()
        }

        override fun regainFocus() {
            if (focusHeld) return
            val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            @Suppress("DEPRECATION")
            val result = audio.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        override fun dispatch(event: KeyEvent): Boolean {
            val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) send(index)
            return true
        }

        protected fun send(index: Int) {
            val sent = controller?.sendMediaButton(index) ?: false
            Log.i(TAG, "media key -> CarPlay $index sent=$sent")
        }

        protected open fun release() {
            val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(focusListener)
            focusHeld = false
            controller = null
            context = null
        }

        private fun onPlaybackChanged(playing: Boolean) {
            if (playing) mainHandler.post { regainFocus() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private class Api21Backend : FocusBackend() {
        private var session: MediaSession? = null

        override fun update(active: Boolean) {
            val currentContext = context ?: return
            if (controller == null) return
            if (session == null) {
                // A car that hands the wheel over through its own input service sends the press on
                // as an ACTION_MEDIA_BUTTON aimed at whichever receiver the framework considers the
                // media button one. A session without these declares no interest and is passed over,
                // leaving the wheel on the head unit's own player.
                val mediaButtonReceiver = PendingIntent.getBroadcast(
                    currentContext,
                    0,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(
                        ComponentName(currentContext, CarPlayMediaButtonReceiver::class.java),
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session = MediaSession(currentContext, "DiPlay CarPlay").apply {
                    setFlags(
                        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
                    )
                    setMediaButtonReceiver(mediaButtonReceiver)
                    setCallback(CarPlayMediaCallback { index, _ -> send(index) }, mainHandler)
                    isActive = true
                }
            }
            if (active) regainFocus()
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            session?.setPlaybackState(
                PlaybackState.Builder().setActions(actions).setState(
                    if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                ).build(),
            )
        }

        override fun release() {
            session?.apply { isActive = false; release() }
            session = null
            super.release()
        }
    }

    @Suppress("DEPRECATION")
    private class LegacyBackend : FocusBackend() {
        private var remote: RemoteControlClient? = null
        private var receiver: ComponentName? = null

        override fun update(active: Boolean) {
            val currentContext = context ?: return
            val audio = currentContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (remote == null) {
                receiver = ComponentName(currentContext, CarPlayMediaButtonReceiver::class.java)
                val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver)
                val pending = PendingIntent.getBroadcast(currentContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
                remote = RemoteControlClient(pending).apply {
                    setTransportControlFlags(
                        RemoteControlClient.FLAG_KEY_MEDIA_PLAY or RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
                            RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
                            RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS,
                    )
                }
                audio.registerMediaButtonEventReceiver(receiver)
                audio.registerRemoteControlClient(remote)
            }
            if (active) regainFocus()
            remote?.setPlaybackState(
                if (active) RemoteControlClient.PLAYSTATE_PLAYING else RemoteControlClient.PLAYSTATE_PAUSED,
            )
        }

        override fun release() {
            val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio != null) {
                remote?.let { audio.unregisterRemoteControlClient(it) }
                receiver?.let { audio.unregisterMediaButtonEventReceiver(it) }
            }
            remote = null
            receiver = null
            super.release()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
internal class CarPlayMediaCallback(
    private val send: (Int, Bundle?) -> Unit,
) : MediaSession.Callback() {
    override fun onPlay() = send(CarPlayMediaButton.PLAY, null)
    override fun onPause() = send(CarPlayMediaButton.PAUSE, null)
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, null)
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, null)

    override fun onMediaButtonEvent(intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val index = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
            else -> CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) send(index, null)
        return true
    }
}

class CarPlayMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (CarPlayMediaKeys.dispatch(event)) abortBroadcast()
    }
}
