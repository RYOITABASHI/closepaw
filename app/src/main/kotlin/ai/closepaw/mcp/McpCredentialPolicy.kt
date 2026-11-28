package ai.closepaw.mcp

/**
 * Who may read the MCP bearer token through [ai.closepaw.app.McpCredentialProvider].
 *
 * Only the adb shell (app id 2000) and root (app id 0) are trusted: anyone holding adb shell can
 * already drive the device almost completely, so serving them the token adds no new trust
 * boundary, while every app uid is refused. The provider never creates or regenerates a token
 * (an absent token yields no row), so reading it can never invalidate a running client.
 *
 * Pure JVM: no Android imports.
 */
object McpCredentialPolicy {
    const val ROOT_APP_ID = 0
    const val SHELL_APP_ID = 2000
    const val TOKEN_PATH = "/token"
    const val COLUMN_TOKEN = "token"
    private const val PER_USER_RANGE = 100_000

    sealed interface Decision {
        data object Denied : Decision
        data class Empty(val reason: String) : Decision
        data class Token(val token: String) : Decision
    }

    fun isTrustedCaller(callingUid: Int): Boolean {
        if (callingUid < 0) return false
        val appId = callingUid % PER_USER_RANGE
        return appId == ROOT_APP_ID || appId == SHELL_APP_ID
    }

    /** [serverEnabled] and [readToken] are only invoked for a trusted caller on [TOKEN_PATH]. */
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
