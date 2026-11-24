package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperVirtualDisplayLifecycleTest {
    @Test
    fun helperRequiresOptInAndApi33() {
        assertFalse(VirtualDisplayLifecyclePolicy.useHelper(32, enabled = true))
        assertFalse(VirtualDisplayLifecyclePolicy.useHelper(34, enabled = false))
        assertTrue(VirtualDisplayLifecyclePolicy.useHelper(33, enabled = true))
        assertTrue(VirtualDisplayLifecyclePolicy.useHelper(34, enabled = true))
    }

    @Test
    fun createReplyRequiresAnActualVirtualDisplayOrAnExplicitRejection() {
        val create = TaskHelperWire.CREATE_DISPLAY
        assertTrue(matchesDisplayResult(create, -1, create, 42, true))
        assertFalse(matchesDisplayResult(create, -1, create, 0, true))
        assertFalse(matchesDisplayResult(create, -1, create, -1, true))
        assertTrue(matchesDisplayResult(create, -1, create, -1, false))
        assertFalse(matchesDisplayResult(create, -1, create, 42, false))
        assertFalse(matchesDisplayResult(create, -1, create, -1, null))
        assertFalse(matchesDisplayResult(create, -1, create, 42L, true))
    }

    @Test
    fun mutationReplyMustMatchOperationAndTarget() {
        val surface = TaskHelperWire.SET_DISPLAY_SURFACE
        val ime = TaskHelperWire.SET_DISPLAY_IME_POLICY
        assertTrue(matchesDisplayResult(surface, 42, surface, 42, true))
        assertTrue(matchesDisplayResult(surface, 42, surface, 42, false))
        assertFalse(matchesDisplayResult(surface, 42, surface, 41, true))
        assertFalse(matchesDisplayResult(surface, 42, ime, 42, true))
    }

    @Test
    fun optionalFlagsRetryOnlyAfterRejection() {
        val attempts = mutableListOf<Int>()
        val id = createWithOptionalFlags(0xE49, 0x14000) {
            attempts += it
            if (attempts.size == 1) -1 else 42
        }
        assertEquals(42, id)
        assertEquals(listOf(0x14E49, 0xE49), attempts)
    }

    @Test
    fun optionalFlagsDoNotRetryUnconfirmedCreate() {
        val attempts = mutableListOf<Int>()
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            createWithOptionalFlags(0xE49, 0x14000) {
                attempts += it
                throw java.io.IOException("missing result")
            }
        }
        assertEquals(listOf(0x14E49), attempts)
    }
}
