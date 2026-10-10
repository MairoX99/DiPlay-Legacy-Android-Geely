package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.IphoneUsbException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wired path branches on two Lockdown error codes, and both reach it as text inside the message the client
 * builds — on the car it was `Lockdown StartSession failed: InvalidHostID`. Renaming either code in the client
 * would quietly file the failure under the general bring-up reason, which is retried like any other fault, so
 * the two codes are pinned here rather than only where they are produced.
 */
class LockdownErrorReportTest {
    @Test fun theHostIdRefusalIsNotConfusedWithThePairRecordCode() {
        val refused = IphoneUsbException.Protocol("Lockdown StartSession failed: InvalidHostID")

        assertTrue(reportsLockdownError(refused, "InvalidHostID"))
        assertFalse(reportsLockdownError(refused, "InvalidPairRecord"))
    }

    @Test fun aCodeIsStillFoundUnderTheThrowablesThatWrapIt() {
        val wrapped = IllegalStateException(
            "wired bring-up failed",
            IphoneUsbException.Protocol("Lockdown Pair failed: InvalidPairRecord"),
        )

        assertTrue(reportsLockdownError(wrapped, "InvalidPairRecord"))
        assertFalse(reportsLockdownError(wrapped, "InvalidHostID"))
    }

    @Test fun anOrdinaryWiredFaultCarriesNeitherCode() {
        val failure = IllegalStateException("Could not open the iPhone NCM connection")

        assertFalse(reportsLockdownError(failure, "InvalidHostID"))
        assertFalse(reportsLockdownError(failure, "InvalidPairRecord"))
    }
}
