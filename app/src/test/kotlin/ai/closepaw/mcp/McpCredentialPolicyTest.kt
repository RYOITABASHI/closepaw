package ai.closepaw.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCredentialPolicyTest {
    private val token = "a".repeat(64)

    @Test fun shellAndRootReceiveTheToken() {
        for (uid in listOf(2000, 0, 1_002_000)) {
            assertEquals(
                uid.toString(),
                McpCredentialPolicy.Decision.Token(token),
                McpCredentialPolicy.decide(uid, "/token", { true }, { token }),
            )
        }
    }

    @Test fun appUidsAreDeniedWithoutReadingTheToken() {
        for (uid in listOf(1000, 1001, 10123, 99_999, 1_010_050, -1)) {
            var read = false
            assertEquals(
                uid.toString(),
                McpCredentialPolicy.Decision.Denied,
                McpCredentialPolicy.decide(uid, "/token", { true }, { read = true; token }),
            )
            assertFalse(read)
        }
    }

    @Test fun disabledServerYieldsNoRowWithoutReadingTheToken() {
        var read = false
        assertEquals(
            McpCredentialPolicy.Decision.Empty("server_disabled"),
            McpCredentialPolicy.decide(2000, "/token", { false }, { read = true; token }),
        )
        assertFalse(read)
    }

    @Test fun missingOrMalformedTokenYieldsNoRow() {
        for (value in listOf(null, "", "A".repeat(64), "a".repeat(63), "z".repeat(64))) {
            assertEquals(
                McpCredentialPolicy.Decision.Empty("no_token"),
                McpCredentialPolicy.decide(2000, "/token", { true }, { value }),
            )
        }
    }

    @Test fun otherPathsYieldNoRow() {
        for (path in listOf("/", "/token/x", "/tokens", null)) {
            assertEquals(
                McpCredentialPolicy.Decision.Empty("unknown_path"),
                McpCredentialPolicy.decide(2000, path, { true }, { token }),
            )
        }
    }

    @Test fun trustedCallerIsShellOrRootInAnyUser() {
        assertTrue(McpCredentialPolicy.isTrustedCaller(2000))
        assertTrue(McpCredentialPolicy.isTrustedCaller(0))
        assertTrue(McpCredentialPolicy.isTrustedCaller(1_000_000))
        assertFalse(McpCredentialPolicy.isTrustedCaller(10_056))
        assertFalse(McpCredentialPolicy.isTrustedCaller(-2000))
    }
}
