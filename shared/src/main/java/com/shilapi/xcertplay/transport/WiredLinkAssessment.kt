package com.shilapi.xcertplay.transport

/**
 * Which rung of the wired iPhone link is on.
 *
 * The MFi rungs above [SEARCHING] are deliberately absent. A bring-up that has not reached the USB
 * stage yet is not a cable fault, and a report that blamed one would be a guess.
 */
enum class WiredLinkStep {
    /** The wired route is not the selected transport. Nothing below applies. */
    NOT_WIRED,
    /** No device bring-up can use, or one it can and Android has not granted access to yet. */
    SEARCHING,
    /** The CarPlay configuration request has been sent and the phone has not come back in that shape. */
    CARPLAY_CONFIGURATION,
    /** USBMUX, iAP2 and NCM are being opened. */
    DATA_PATHS,
    /** The iAP2 control path: connecting, running, or ended. */
    CONTROL,
}

/**
 * What explains the rung, when anything does.
 *
 * The two bus verdicts look identical to a user — "searching for an iPhone" forever — and each one
 * calls for a different thing to be done about it. Telling them apart is the whole point; the
 * permission waits already say what happened to them and are not repeated here.
 */
enum class WiredLinkFinding {
    NONE,
    /** Nothing is on the bus but this app's own hardware: port, cable, or the plug's orientation. */
    NO_USB_DEVICE,
    /** Something this app does not own is attached, and it is not an Apple device. */
    NOT_APPLE_DEVICE,
    /** Detach and reattach repeated with no session in between: the plug is loose. */
    LOOSE_CONTACT,
    /** The same repeats with a session in between: the supply dipped and the phone reset. */
    SUPPLY_DIP,
}

/**
 * What the wired link looks like now. Every field is a count or a flag — no USB, no clock, no I/O —
 * so a test can drive the assessment straight.
 *
 * [deviceCount] only carries information while [step] is [WiredLinkStep.SEARCHING]: that is the only
 * state in which "no device bring-up can use" is being asserted, and so the only state in which the
 * count says why.
 */
data class WiredLinkObservation(
    val wired: Boolean,
    val step: WiredLinkStep,
    /** Discovery polls in a row that found no device bring-up can use. */
    val emptyPolls: Int,
    /** Devices on the bus that this app does not own, whatever their vendor. */
    val deviceCount: Int,
    /**
     * Detach-then-reattach rounds that came back quickly. Two that are minutes apart are two
     * deliberate unplugs rather than a loose plug, so the recorder only counts the quick ones — and
     * it counts none of the ones a re-enumeration causes, which happen on every good connection.
     */
    val detachCycles: Int,
    /** True once a control session has run during the rounds being counted — since the first of them. */
    val sessionRan: Boolean,
)

data class WiredLinkReport(
    val step: WiredLinkStep,
    val finding: WiredLinkFinding,
    val emptyPolls: Int,
) {
    /**
     * One line for the handshake log. The `usb/` in it is what gets it promoted there, and the
     * `DIAG ` prefix keeps it apart from the `STEP ` lines that report progress.
     */
    fun logLine(): String =
        "DIAG usb/link: step=${step.name.lowercase()} finding=${finding.name.lowercase()} " +
            "polls=$emptyPolls"

    /** A finding worth a log line. "Nothing wrong yet" is not worth one. */
    val worthReporting: Boolean get() = finding != WiredLinkFinding.NONE
}

object WiredLinkAssessment {
    /**
     * Polls of the availability timer before an empty search is called stuck. The first poll runs as the
     * search is asked for and the timer is two seconds, so three more of them are the six seconds a user
     * gives a cable before deciding nothing happened.
     */
    const val STUCK_POLLS = 4

    /**
     * Cycles before the plug is called loose. Nothing ever ran, so nothing was drawing current and a
     * supply fault has nothing to explain.
     */
    const val LOOSE_CONTACT_CYCLES = 3

    /**
     * Cycles before the supply is blamed. A session ran, so the phone was drawing current, and a dip
     * under load is what makes it reset and come back.
     */
    const val SUPPLY_DIP_CYCLES = 2

    fun assess(observed: WiredLinkObservation): WiredLinkReport {
        if (!observed.wired) {
            // The rung the caller passed describes a link that is not the one in use, so it is not
            // carried through: a report that said SEARCHING for the wireless hop would be fiction.
            return WiredLinkReport(WiredLinkStep.NOT_WIRED, WiredLinkFinding.NONE, observed.emptyPolls)
        }
        // Checked before the rung, because the moment a cycle completes is the moment the phone is
        // back — so the round that proves a dip is never seen from a searching rung, and gating this
        // on SEARCHING would leave the verdict unreachable.
        val cycles = if (observed.sessionRan) SUPPLY_DIP_CYCLES else LOOSE_CONTACT_CYCLES
        if (observed.detachCycles >= cycles) {
            return report(
                observed,
                if (observed.sessionRan) WiredLinkFinding.SUPPLY_DIP else WiredLinkFinding.LOOSE_CONTACT,
            )
        }
        // Every other rung has a device, so the bus has nothing left to explain about it.
        if (observed.step != WiredLinkStep.SEARCHING) {
            return report(observed, WiredLinkFinding.NONE)
        }
        // Below the threshold a user has simply not had time to plug the phone in yet.
        if (observed.emptyPolls < STUCK_POLLS) {
            return report(observed, WiredLinkFinding.NONE)
        }
        return report(observed, busFinding(observed))
    }

    /**
     * Two faults read the same on screen, and there is no third. An Apple device on the bus is one
     * this app will offer to use, because it accepts any Apple product ID — the runtime config
     * carries none on purpose — so "attached but outside the ones we would use" cannot arise.
     */
    private fun busFinding(observed: WiredLinkObservation): WiredLinkFinding =
        if (observed.deviceCount == 0) {
            WiredLinkFinding.NO_USB_DEVICE
        } else {
            WiredLinkFinding.NOT_APPLE_DEVICE
        }

    private fun report(observed: WiredLinkObservation, finding: WiredLinkFinding) =
        WiredLinkReport(observed.step, finding, observed.emptyPolls)
}

/**
 * Counts how often the wired link left and came straight back.
 *
 * This cannot live on the controller. Losing an iPhone mid-session is handled by failing and
 * rebuilding the controller, so the event being counted is the one that destroys the counter that
 * would count it — and a cable that dips under load is only separable from two deliberate unplugs by
 * remembering across that rebuild. Hence [shared], which outlives the controller.
 *
 * The clock is a parameter rather than a call inside, so a test can drive a re-plug in two seconds
 * without waiting two seconds.
 */
class WiredLinkCycles(private val quickReattachNanos: Long = QUICK_REATTACH_NANOS) {
    private var detachedAtNanos = 0L
    private var awaitingReattach = false
    private var cycles = 0
    private var ran = false

    /** The leaving half of a re-plug. Only worth calling when nothing asked the phone to leave. */
    @Synchronized
    fun noteDetach(nowNanos: Long) {
        // A detach that finds no rounds counted yet opens a fresh run of them, so a session that ran before
        // it belongs to the run before and must not answer for this one.
        if (cycles == 0) ran = false
        detachedAtNanos = nowNanos
        awaitingReattach = true
    }

    /** The returning half, counted only when it followed a [noteDetach] and followed it quickly. */
    @Synchronized
    fun noteReattach(nowNanos: Long) {
        if (!awaitingReattach) return
        awaitingReattach = false
        if (nowNanos - detachedAtNanos <= quickReattachNanos) cycles += 1
    }

    /** A control session reached `RunningControl`, so the phone has been drawing current. */
    @Synchronized
    fun noteSessionRan() {
        ran = true
    }

    /**
     * The verdict has been reported on. It is an event rather than a state — the rounds that proved it
     * are over by the time it is said — so the next ones have to earn a verdict of their own instead of
     * restating this one forever.
     *
     * The session goes with the cycles, because what the two verdicts are told apart by is whether a
     * session ran on *these* rounds. A session that ran an hour ago would answer that question for
     * every re-plug afterwards, and a loose plug — which needs three rounds precisely because nothing
     * was drawing current — would become unreachable for the rest of the process.
     */
    @Synchronized
    fun noteReported() {
        cycles = 0
        ran = false
    }

    @Synchronized
    fun cycles(): Int = cycles

    @Synchronized
    fun sessionRan(): Boolean = ran

    companion object {
        /** A re-plug slower than this is a person walking to the phone, not a loose plug. */
        const val QUICK_REATTACH_NANOS = 5_000_000_000L

        /** The instance the wired link uses, held for the life of the process. */
        val shared = WiredLinkCycles()
    }
}
