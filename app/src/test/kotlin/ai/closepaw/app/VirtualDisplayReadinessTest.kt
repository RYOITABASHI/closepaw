package ai.closepaw.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayReadinessTest {
    @Test
    fun `unpaired disconnected without known port`() {
        assertFalse(VirtualDisplayReadiness.isReady(paired = false, connected = false, hasKnownPort = false))
    }

    @Test
    fun `unpaired disconnected with known port`() {
        assertFalse(VirtualDisplayReadiness.isReady(paired = false, connected = false, hasKnownPort = true))
    }

    @Test
    fun `unpaired connected without known port`() {
        assertFalse(VirtualDisplayReadiness.isReady(paired = false, connected = true, hasKnownPort = false))
    }

    @Test
    fun `unpaired connected with known port`() {
        assertFalse(VirtualDisplayReadiness.isReady(paired = false, connected = true, hasKnownPort = true))
    }

    @Test
    fun `paired disconnected without known port`() {
        assertFalse(VirtualDisplayReadiness.isReady(paired = true, connected = false, hasKnownPort = false))
    }

    @Test
    fun `paired disconnected with known port`() {
        assertTrue(VirtualDisplayReadiness.isReady(paired = true, connected = false, hasKnownPort = true))
    }

    @Test
    fun `paired connected without known port`() {
        assertTrue(VirtualDisplayReadiness.isReady(paired = true, connected = true, hasKnownPort = false))
    }

    @Test
    fun `paired connected with known port`() {
        assertTrue(VirtualDisplayReadiness.isReady(paired = true, connected = true, hasKnownPort = true))
    }
}
