package com.shilapi.xcertplay.transport

/**
 * Decides whether a run of failed bulk OUT writes is a fault or the phone holding the NCM path
 * while its "allow CarPlay" dialog waits for its user.
 *
 * The phone holds bulk OUT NAKed from the moment the accessory asks it to start CarPlay until that
 * question is answered, and the dialog itself gives up after about twenty seconds. Android reports
 * that hold as the same failed transfer a latched endpoint reports, so a failure count alone ends a
 * session the user is still looking at. [arm] opens the window when the phone is asked to start
 * CarPlay; the window closes as soon as an AirPlay session is live, or when [graceMillis] passes
 * with none. After that a run of failures is a fault again, and the reopen asks the phone a second
 * time.
 *
 * The caller passes the clock, so every transition is testable without a device.
 */
internal class NcmWriteWindow(private val graceMillis: Long = AUTHORIZATION_GRACE_MILLIS) {
    enum class Verdict {
        /** The phone is waiting for its user; retry without counting. */
        EXPECTED,

        /** A run of failures, judged as it was before this window existed. */
        FAULT,

        /** The window passed with no AirPlay session; end the session and ask the phone again. */
        EXPIRED,
    }

    private var askedAtMillis: Long? = null

    /** Opens the window. Later calls keep the first moment, so the grace is measured once. */
    fun arm(nowMillis: Long) {
        if (askedAtMillis == null) askedAtMillis = nowMillis
    }

    fun verdict(nowMillis: Long, sessionLive: Boolean): Verdict {
        if (sessionLive) return Verdict.FAULT
        val askedAt = askedAtMillis ?: return Verdict.FAULT
        return if (nowMillis - askedAt >= graceMillis) Verdict.EXPIRED else Verdict.EXPECTED
    }

    companion object {
        /** The phone's dialog gives up after about twenty seconds; allow for a slow read and a slow tap. */
        const val AUTHORIZATION_GRACE_MILLIS = 45_000L
    }
}
