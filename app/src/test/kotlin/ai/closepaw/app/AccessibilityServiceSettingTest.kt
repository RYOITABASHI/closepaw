package ai.closepaw.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceSettingTest {
    @Test
    fun detectsFullAndShortComponentNames() {
        assertTrue(AccessibilityServiceSetting.containsAgent("other/.Service:ai.closepaw/.app.AgentService"))
        assertTrue(AccessibilityServiceSetting.containsAgent("ai.closepaw/ai.closepaw.app.AgentService"))
        assertFalse(AccessibilityServiceSetting.containsAgent("other/ai.closepaw.app.AgentService"))
        assertFalse(AccessibilityServiceSetting.containsAgent("ai.closepaw/.app.AgentServiceExtra"))
    }

    @Test
    fun appendsWithoutRewritingOtherEntries() {
        val existing = " other/.Service:other/other.Service::other/.Service: "
        assertEquals("$existing:${AccessibilityServiceSetting.AGENT}", AccessibilityServiceSetting.withAgent(existing))
        assertEquals(":::${AccessibilityServiceSetting.AGENT}", AccessibilityServiceSetting.withAgent("::"))
    }

    @Test
    fun leavesOriginalValueWhenEitherAgentFormIsPresent() {
        for (agent in listOf(AccessibilityServiceSetting.AGENT, "ai.closepaw/.app.AgentService")) {
            val existing = " other/.Service:$agent:other/other.Service::other/.Service "
            assertEquals(existing, AccessibilityServiceSetting.withAgent(existing))
        }
    }

    @Test
    fun handlesUnsetValues() {
        for (value in listOf(null, "", "null")) {
            assertFalse(AccessibilityServiceSetting.containsAgent(value))
            assertEquals(AccessibilityServiceSetting.AGENT, AccessibilityServiceSetting.withAgent(value))
        }
    }

    @Test
    fun verifiesAppendAndRejectsLostEntriesOrConcurrentEdits() {
        val original = " other/.Service:other/other.Service:other/other.Service"
        val before = AccessibilityServiceSetting.entries(original)
        val written = AccessibilityServiceSetting.withAgent(original)
        assertTrue(AccessibilityServiceSetting.verifyUpdate(before, written, written))
        assertTrue(AccessibilityServiceSetting.verifyUpdate(before, written, "$original:ai.closepaw/.app.AgentService"))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, " other/.Service:other/other.Service:${AccessibilityServiceSetting.AGENT}"))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, "other/other.Service: other/.Service:other/other.Service:${AccessibilityServiceSetting.AGENT}"))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, written.substringAfter(':')))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, "$written:added/.Service"))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, original))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, null))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, AccessibilityServiceSetting.AGENT, AccessibilityServiceSetting.AGENT))
    }

    @Test
    fun removalKeepsOtherEntriesAndVerifiesBothAgentFormsAreGone() {
        val original = " other/.Service:ai.closepaw/.app.AgentService::${AccessibilityServiceSetting.AGENT}:other/other.Service "
        val before = AccessibilityServiceSetting.entries(original)
        val written = AccessibilityServiceSetting.withoutAgent(original)
        assertEquals(" other/.Service::other/other.Service ", written)
        assertTrue(AccessibilityServiceSetting.verifyUpdate(before, written, written, agentExpected = false))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, original, agentExpected = false))
        assertFalse(AccessibilityServiceSetting.verifyUpdate(before, written, "", agentExpected = false))
        assertTrue(AccessibilityServiceSetting.verifyUpdate(
            listOf(AccessibilityServiceSetting.AGENT), "", "null", agentExpected = false,
        ))
    }
}
