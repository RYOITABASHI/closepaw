package ai.closepaw.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainSessionAdmissionTest {
    @Test
    fun overlappingInternalLifetimesKeepBrainAdmissionClosedUntilTheLastRelease() {
        val admission = BrainSessionAdmission
        val provisional = Any()
        val session = Any()
        val brain = Any()
        try {
            assertTrue(admission.tryClaimInternal(provisional))
            assertFalse(admission.gate.tryBeginStart(brain))
            assertTrue(admission.tryClaimInternal(session))
            admission.releaseInternal(provisional)

            assertTrue(admission.tryClaimInternal(session))
            assertFalse(admission.gate.tryBeginStart(brain))
            admission.releaseInternal(session)

            assertTrue(admission.gate.tryBeginStart(brain))
            assertFalse(admission.tryClaimInternal(provisional))
            admission.gate.startAdopted(brain)
            try {
                assertFalse(admission.tryClaimInternal(session))
            } finally {
                admission.gate.sessionEnded(brain)
            }
            assertTrue(admission.tryClaimInternal(session))
        } finally {
            admission.releaseInternal(provisional)
            admission.releaseInternal(session)
            admission.gate.startAborted(brain)
        }
    }
}
