package ai.closepaw.mcp

import ai.closepaw.mcp.McpCredentialPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCredentialPolicyTest {
    private val token = "a".repeat(64)

    private fun decide(
        uid: Int,
        path: String? = McpCredentialPolicy.TOKEN_PATH,
        enabled: Boolean = true,
        stored: String? = token,
        onRead: () -> Unit = {},
    ) = McpCredentialPolicy.decide(uid, path, { enabled }, { onRead(); stored })

    @Test
    fun shellAndRootReceiveTheStoredToken() {
        assertEquals(Decision.Token(token), decide(2000))
        assertEquals(Decision.Token(token), decide(0))
        // Secondary-user shell (u10) maps to the same app id.
        assertEquals(Decision.Token(token), decide(10 * 100_000 + 2000))
    }

    @Test
    fun anyOtherCallerIsDeniedWithoutTouchingTheToken() {
        var reads = 0
        for (uid in listOf(1000, 1001, 10_123, 99_999, 10 * 100_000 + 10_050, -1)) {
            assertEquals(Decision.Denied, decide(uid, onRead = { reads++ }))
        }
        assertEquals(0, reads)
    }

    @Test
    fun disabledServerOrMissingTokenYieldsEmpty() {
        assertEquals(Decision.Empty("server_disabled"), decide(2000, enabled = false))
        assertEquals(Decision.Empty("no_token"), decide(2000, stored = null))
        assertEquals(Decision.Empty("no_token"), decide(2000, stored = "not-hex"))
    }

    @Test
    fun disabledServerNeverReadsTheToken() {
        var reads = 0
        decide(2000, enabled = false, onRead = { reads++ })
        assertEquals(0, reads)
    }

    @Test
    fun onlyTheTokenPathIsAnswered() {
        assertEquals(Decision.Empty("unknown_path"), decide(2000, path = "/"))
        assertEquals(Decision.Empty("unknown_path"), decide(2000, path = "/token/x"))
        assertEquals(Decision.Empty("unknown_path"), decide(2000, path = null))
    }

    @Test
    fun trustedCallerClassification() {
        assertTrue(McpCredentialPolicy.isTrustedCaller(0))
        assertTrue(McpCredentialPolicy.isTrustedCaller(2000))
        assertFalse(McpCredentialPolicy.isTrustedCaller(2001))
        assertFalse(McpCredentialPolicy.isTrustedCaller(10_000))
    }
}
