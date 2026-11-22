package ai.closepaw.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
