package ai.closepaw.platform.virtualdisplay
import ai.closepaw.taskhelper.TaskHelperProtocol
import org.junit.Assert.*
import org.junit.Test
class SpikeTapWireTest {
    @Test fun requestRoundTrip() {
        val encoded = TaskHelperProtocol.encodeTapRequest(42, 12.5f, -3f)
        assertEquals("42\n12.5\n-3.0", encoded)
        val request = TaskHelperProtocol.parseTapRequest(encoded)
        assertEquals(42, request.displayId)
        assertEquals(12.5f, request.x, 0f)
        assertEquals(-3f, request.y, 0f)
        val maxDisplay = TaskHelperProtocol.encodeTapRequest(Int.MAX_VALUE, 0f, 0f)
        assertEquals(Int.MAX_VALUE, TaskHelperProtocol.parseTapRequest(maxDisplay).displayId)
        assertTrue(runCatching { TaskHelperProtocol.parseTapRequest("1\nNaN\n0") }.isFailure)
    }
    @Test fun resultRoundTrip() {
        for (down in listOf(null, false, true)) for (up in listOf(null, false, true)) {
            val error = "InvocationTargetException: denied\nwith details"
            val fields = TaskHelperProtocol.parseTapResult(TaskHelperProtocol.encodeTapResult(true, down, up, error))
            assertArrayEquals(arrayOf("true", down.toString(), up.toString(), error), fields)
        }
        assertArrayEquals(arrayOf("false", "null", "null", ""), TaskHelperProtocol.parseTapResult(TaskHelperProtocol.encodeTapResult(false, null, null, null)))
    }
    @Test fun rejectsMalformed() {
        for (value in listOf("", "true\ntrue", "TRUE\nnull\nnull\n", "true\nwat\nnull\n"))
            assertTrue(runCatching { TaskHelperProtocol.parseTapResult(value) }.isFailure)
    }
}
