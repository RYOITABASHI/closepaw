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
