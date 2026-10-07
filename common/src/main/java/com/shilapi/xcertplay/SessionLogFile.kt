package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val state = stateFor(file)
    private var closed = false
    fun reset(header: String) = synchronized(state) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            state.length = 0L
            append(header)
        }
    }
    fun append(line: String) = synchronized(state) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            val payload = (safe + "\n").toByteArray(Charsets.UTF_8)
            if (lengthBytes() > MAX_BYTES) {
                rotate()
                file.writeText("")
                state.length = 0L
            }
            file.appendBytes(payload)
            state.length += payload.size
        }
        Unit
    }
    private fun lengthBytes(): Long {
        if (state.length < 0L) {
            state.length = if (file.isFile) file.length() else 0L
        }
        return state.length
    }
    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }
    override fun close() = synchronized(state) { closed = true }

    /**
     * One log file can outlive one instance: an activity is recreated while the previous
     * session's writer is still draining. Both hold a [SessionLogFile] for the same path, so
     * they share the monitor and the byte count — otherwise the newcomer's truncate leaves the
     * old writer's cached size over [MAX_BYTES], and its next append rotates and wipes the
     * fresh file.
     */
    private class State {
        /** Byte length of the file. Negative until the first read. */
        var length = -1L
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L
        /** Keyed by absolute path. Bounded by the number of distinct log paths, which is one. */
        private val states = ConcurrentHashMap<String, State>()
        private fun stateFor(file: File): State =
            states.computeIfAbsent(file.absolutePath) { State() }
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
