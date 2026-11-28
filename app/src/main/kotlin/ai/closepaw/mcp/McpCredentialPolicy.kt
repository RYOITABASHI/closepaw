package ai.closepaw.mcp

/**
 * Who may read the MCP bearer token through [ai.closepaw.app.McpCredentialProvider].
 *
 * Only the adb shell (app id 2000) and root (0) may read it. Those callers already control the
 * device: they can grant permissions, install packages, and inject input. So exposing the token
 * to them adds no new trust boundary. What it adds is a zero-touch way for an operator agent with
 * an adb route to recover the token after losing its local copy, without the owner opening
 * Settings and without regenerating the token (which would kill other clients).
 *
 * Read-only by construction: the policy never creates or rotates a token. A disabled server or
 * a missing/malformed token yields [Decision.Empty].
 *
 * Pure JVM.
 */
object McpCredentialPolicy {
    const val ROOT_APP_ID = 0
    const val SHELL_APP_ID = 2000
    const val TOKEN_PATH = "/token"
    const val COLUMN_TOKEN = "token"
    private const val PER_USER_RANGE = 100_000

    sealed interface Decision {
        /** Caller is not root/shell: refuse with a SecurityException. */
        data object Denied : Decision

        /** Allowed caller, but nothing to hand out (unknown path, server off, no token). */
        data class Empty(val reason: String) : Decision

        data class Token(val token: String) : Decision
    }

    fun isTrustedCaller(callingUid: Int): Boolean {
        if (callingUid < 0) return false
        val appId = callingUid % PER_USER_RANGE
        return appId == ROOT_APP_ID || appId == SHELL_APP_ID
    }

    /**
     * [readToken] is called only for a trusted caller asking for [TOKEN_PATH] while the server
     * is enabled. It is never called otherwise, so an untrusted query never touches the Keystore.
     */
    fun decide(
        callingUid: Int,
        path: String?,
        serverEnabled: () -> Boolean,
        readToken: () -> String?,
    ): Decision {
        if (!isTrustedCaller(callingUid)) return Decision.Denied
        if (path != TOKEN_PATH) return Decision.Empty("unknown_path")
        if (!serverEnabled()) return Decision.Empty("server_disabled")
        val token = readToken()?.takeIf(McpAuth::isWellFormedToken) ?: return Decision.Empty("no_token")
        return Decision.Token(token)
    }
}
