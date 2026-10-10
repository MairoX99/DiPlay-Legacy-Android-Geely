package com.shilapi.xcertplay

import android.util.Log
import java.util.Locale

/**
 * `android.util.Log`, with the line also offered to the session log.
 *
 * The session file only ever held what the host page chose to tell it. Everything the transport, media
 * and network code logged went to logcat and nowhere else — 209 call sites of it — and on a head unit
 * with no adb those lines are the only description of why the link failed. This facade keeps the logcat
 * entry and adds the file one.
 *
 * [DiagSink.line] only queues; it performs no I/O, so this is safe to call from a transport thread.
 */
object DiagLog {
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        DiagSink.line(line(tag, message, null))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        DiagSink.line(line(tag, message, null))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        DiagSink.line(line(tag, message, error))
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        DiagSink.line(line(tag, message, error))
    }

    /** The class and message only: a stack trace belongs to the crash handler, not to every warning. */
    private fun line(tag: String, message: String, error: Throwable?): String =
        if (error == null) {
            "$tag: $message"
        } else {
            String.format(Locale.US, "%s: %s [%s: %s]", tag, message, error.javaClass.simpleName, error.message)
        }
}
