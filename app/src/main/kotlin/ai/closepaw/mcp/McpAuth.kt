package ai.closepaw.mcp

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Bearer-token and Origin checks for ClosePaw's MCP endpoint. Mirrors Shelly's MCP server
 * (scripts/shelly-mcp-server.js): a pre-shared per-device bearer token checked on EVERY request,
 * compared in constant time over fixed-length SHA-256 digests (so neither the content nor the
 * length of the presented header leaks through timing), and a present Origin header that is not
 * loopback refused with 403 (the transport spec's DNS-rebinding guard; MCP clients such as
 * Claude Code / Codex send no Origin).
 *
 * Pure JVM: no Android imports, unit tested in ShellyBackgroundRunTest.
 */
object McpAuth {
    const val TOKEN_BYTES = 32

    private val TOKEN_RE = Regex("^[0-9a-f]{64}$")
    private val LOOPBACK_ORIGIN =
        Regex("^https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?$", RegexOption.IGNORE_CASE)

    /** 32 random bytes as 64 lowercase hex chars. */
    fun generateToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isWellFormedToken(token: String?): Boolean = token != null && TOKEN_RE.matches(token)

    /**
     * True only when [authorizationHeader] is exactly `Bearer <expectedToken>`. Constant time:
     * both sides are hashed to 32 bytes and compared with [MessageDigest.isEqual]. A missing or
     * malformed expected token (server not configured) rejects everything.
     */
    fun isAuthorized(authorizationHeader: String?, expectedToken: String?): Boolean {
        if (!isWellFormedToken(expectedToken) || authorizationHeader == null) return false
        val presented = sha256(authorizationHeader)
        val expected = sha256("Bearer $expectedToken")
        return MessageDigest.isEqual(presented, expected)
    }

    /** Absent Origin (non-browser client) or a loopback Origin is allowed; anything else is not. */
    fun isOriginAllowed(origin: String?): Boolean = origin == null || LOOPBACK_ORIGIN.matches(origin)

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
