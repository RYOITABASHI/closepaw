package ai.closepaw.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPortDiscoveryTest {
    @Test fun cachedPortSkipsDiscoveryWhenItWorks() = runBlocking {
        assertTrue(connectUsingPorts(37001, { error("Discovery should not run") }) { it == 37001 })
    }

    @Test fun stalePortIsTriedBeforeDiscovery() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(connectUsingPorts(37001, { events += "discover"; 37002 }) {
            events += it.toString()
            it == 37002
        })
        assertEquals(listOf("37001", "discover", "37002"), events)
    }

    @Test fun invalidPortsAndDuplicateCandidatesAreSkipped() = runBlocking {
        assertFalse(connectUsingPorts(0, { 65536 }) { error("Invalid port") })
        var attempts = 0
        assertFalse(connectUsingPorts(37001, { 37001 }) { attempts++; false })
        assertEquals(1, attempts)
    }

    @Test fun discoveryIsRecordedEvenWhenConnectionFailsOrPortMatches() = runBlocking {
        val recorded = mutableListOf<Int>()
        assertFalse(connectUsingPorts(37001, { 37002 }, recorded::add) { false })
        assertFalse(connectUsingPorts(37001, { 37001 }, recorded::add) { false })
        assertFalse(connectUsingPorts(null, { 0 }, recorded::add) { false })
        assertEquals(listOf(37002, 37001), recorded)
    }

    @Test fun onlyAValidDifferentDiscoveryNeedsPromotion() {
        assertEquals(37002, adbPortToPromote(37001, 37002))
        assertEquals(37002, adbPortToPromote(null, 37002))
        assertEquals(null, adbPortToPromote(37001, 37001))
        assertEquals(null, adbPortToPromote(37001, null))
        assertEquals(null, adbPortToPromote(37001, 0))
    }
    @Test fun promotionRequiresADisconnectedChannelAndAValidDifferentPort() {
        assertFalse(shouldAttemptPromotion(true, 37001, 37002))
        assertTrue(shouldAttemptPromotion(false, 37001, 37002))
        assertTrue(shouldAttemptPromotion(false, null, 37002))
        assertFalse(shouldAttemptPromotion(false, 37001, 37001))
        assertFalse(shouldAttemptPromotion(false, 37001, null))
        assertFalse(shouldAttemptPromotion(false, 37001, 65536))
    }
}
