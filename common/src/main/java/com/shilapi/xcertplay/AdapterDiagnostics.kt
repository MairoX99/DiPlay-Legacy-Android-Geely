package com.shilapi.xcertplay

/**
 * The adapter lines the connection page produced, held until a run writes them into its own log.
 *
 * The page's session is not the one that runs CarPlay. The page is where the iPhone is paired — it is the page
 * that is open when the phone taps the name — and its session is disposed as the run starts, while the run's own
 * log is truncated when its session begins. Without something in between, the whole pairing half of the chain
 * would be gone before anything could report it: the connection being accepted, the link key, the encryption.
 * Those are the lines the car is taken out to read, so they are carried over rather than lost to the reset.
 *
 * Bounded: only the attempt in front of the one being started is of any interest, and a page left open all
 * afternoon must not grow.
 */
internal object AdapterDiagnostics {
    private const val MAX_LINES = 64

    private val lines = ArrayDeque<String>()

    /** Called from the stack's own threads, so the list is not free to be read while it is written. */
    fun record(line: String) = synchronized(lines) {
        if (lines.size == MAX_LINES) lines.removeFirst()
        lines.addLast(line)
    }

    /** Takes what is held and forgets it, so a later run is not seeded with an older one's lines. */
    fun drain(): List<String> = synchronized(lines) {
        val held = lines.toList()
        lines.clear()
        held
    }
}
