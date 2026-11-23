package ai.closepaw.app

import java.security.MessageDigest

/**
 * Returns a privacy-preserving correlation fingerprint for a goal.
 * The goal text itself must never be logged.
 */
fun goalFingerprint(goal: String?): String {
    if (goal.isNullOrEmpty()) return "len=0 sha=-"

    val digest = MessageDigest.getInstance("SHA-256").digest(goal.toByteArray(Charsets.UTF_8))
    val prefix = digest.take(4).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return "len=${goal.length} sha=$prefix"
}

/**
 * Identity for result-correlation deduplication: the FULL SHA-256 of the pair.
 * Never use [goalFingerprint] (32-bit prefix) as a dedupe key — distinct valid
 * correlations collide on it (gate r7-2, finding N3).
 */
fun correlationIdentity(reqId: String, nonce: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$reqId\n$nonce".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
