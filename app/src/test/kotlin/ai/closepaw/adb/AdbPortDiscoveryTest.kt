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
}
