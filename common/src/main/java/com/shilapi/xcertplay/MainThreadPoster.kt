package com.shilapi.xcertplay

/**
 * Applies a screen update on the UI thread.
 *
 * The Bluetooth stack reports from its own threads — the HCI reader, the pairing watchdog — and a view may only be
 * touched from the main one. A `setText` from anywhere else throws `CalledFromWrongThreadException`, and that throw
 * used to land inside the state machine, which swallowed it: the line that would have explained a failed attempt
 * never reached the screen, and the handshake log lost it too because the screen line came first.
 */
internal class MainThreadPoster(
    private val isMainThread: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
) {
    fun run(block: () -> Unit) {
        if (isMainThread()) block() else post(block)
    }
}
