package ai.closepaw.app

import ai.closepaw.mcp.BrainCorrelationReservations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalFingerprintTest {
    @Test fun nullGoalHasNoHash() {
        assertEquals("len=0 sha=-", goalFingerprint(null))
    }

    @Test fun emptyGoalHasNoHash() {
        assertEquals("len=0 sha=-", goalFingerprint(""))
    }

    @Test fun hashesAsciiGoal() {
        assertEquals("len=5 sha=2cf24dba", goalFingerprint("hello"))
    }

    @Test fun countsJapaneseCharactersAndHashesUtf8Bytes() {
        assertEquals("len=5 sha=125aeadf", goalFingerprint("こんにちは"))
    }

    @Test fun omitsGoalText() {
        val goal = "Open my private notes"
        assertFalse(goalFingerprint(goal).contains(goal))
    }

    @Test fun correlationIdentityUsesTheFullDigest() {
        val reqId = "1700000000-00000000"
        val firstNonce = "00000000000000000000000000004fb2"
        val secondNonce = "0000000000000000000000000001216c"
        for (nonce in listOf(firstNonce, secondNonce)) {
            assertNotNull(ShellyBackgroundRun.parseResultCorrelation(reqId, nonce))
            assertEquals("len=52 sha=bce4fd61", goalFingerprint("$reqId\n$nonce"))
        }

        val first = correlationIdentity(reqId, firstNonce)
        val second = correlationIdentity(reqId, secondNonce)
        val digest = Regex("[0-9a-f]{64}")
        assertTrue(digest.matches(first))
        assertTrue(digest.matches(second))
        assertFalse(first == second)
        assertEquals(first, correlationIdentity(reqId, firstNonce))
        assertFalse(first == correlationIdentity("1700000000-00000001", firstNonce))

        val reservations = BrainCorrelationReservations()
        assertTrue(reservations.tryReserve(first))
        assertTrue(reservations.tryReserve(second))
        assertTrue(reservations.isReserved(first))
        assertTrue(reservations.isReserved(second))
        reservations.release(first)
        assertFalse(reservations.isReserved(first))
        assertTrue(reservations.isReserved(second))
    }
}
