package ai.closepaw.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedTargetTextTest {
    @Test
    fun enrichedMatchSkipsBlanksAndUsesFirstThreeLabelsInOrder() {
        val descendants = listOf("", "Network", "  ", "Wi-Fi", "Connected", "Ignored")
        assertTrue(matchesObservedTargetText(" ", descendants, "Network | Wi-Fi | Connected"))
    }

    @Test
    fun enrichedMismatchRejectsChangedLabel() {
        assertFalse(matchesObservedTargetText("", listOf("Network", "Disconnected"), "Network | Connected"))
    }

    @Test
    fun leafMatchUsesOwnText() {
        assertTrue(matchesObservedTargetText("Clock", listOf("Alarm"), "Clock"))
    }

    @Test
    fun leafMismatchDoesNotUseDescendants() {
        assertFalse(matchesObservedTargetText("Clock", listOf("Alarm"), "Alarm"))
    }

    @Test
    fun blankHintComparesOwnTextExactly() {
        val descendants = listOf("Network")
        assertTrue(matchesObservedTargetText("", descendants, ""))
        assertTrue(matchesObservedTargetText(" ", descendants, " "))
        assertFalse(matchesObservedTargetText(" ", descendants, ""))
        assertFalse(matchesObservedTargetText("Clock", descendants, ""))
    }
}
