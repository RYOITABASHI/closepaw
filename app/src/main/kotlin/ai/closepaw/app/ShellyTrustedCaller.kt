package ai.closepaw.app

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Shelly trusted-caller bypass for the "External Goal" confirmation dialog.
 *
 * Shelly (dev.shelly.terminal) already obtains the user's approval for every hand-off before it
 * launches ClosePaw, so a goal that is *cryptographically proven* to come from Shelly skips the
 * second confirmation here. Every other caller keeps the existing dialog unchanged.
 *
 * Proof of origin: the caller attaches a [PendingIntent] it created itself as the
 * [EXTRA_CALLER_TOKEN] extra. [PendingIntent.getCreatorPackage] is filled in by the system from
 * the creator's uid and cannot be forged by the sender, so a third-party app cannot mint a token
 * whose creator is Shelly. On top of the package name we pin Shelly's signing certificate, so a
 * different app that somehow got installed under the same package name is still rejected.
 */
object ShellyTrustedCaller {
    private const val TAG = "ShellyTrustedCaller"

    const val EXTRA_CALLER_TOKEN = "shelly_caller_token"
    const val SHELLY_PACKAGE = "dev.shelly.terminal"

    /**
     * SHA-256 of the signing certificate(s) accepted for [SHELLY_PACKAGE].
     *
     * FA:C6:...:3B:9C = certificate of Shelly 8.0.2 (versionCode 2540) as installed on the
     * owner's device on 2026-10-14 (CN=Android Debug). Add the new fingerprint here before
     * Shelly rotates its signing key.
     */
    val PINNED_SHELLY_CERT_SHA256: List<String> = listOf(
        "FAC61745DC0903786FB9EDE62A962B399F7348F0BB6F899B8332667591033B9C",
    )

    /** Pure decision logic, split out so it can be unit tested without Android framework objects. */
    fun isTrusted(
        creatorPackage: String?,
        pinnedCertsSha256: List<String>,
        hasSigningCertificate: (pkg: String, certSha256: ByteArray) -> Boolean,
    ): Boolean {
        if (creatorPackage != SHELLY_PACKAGE) return false
        return pinnedCertsSha256.any { hex ->
            val bytes = hexToBytes(hex) ?: return@any false
            try {
                hasSigningCertificate(creatorPackage, bytes)
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Reads the token from [intent] and verifies it. Never throws. */
    fun verify(intent: Intent, packageManager: PackageManager): Boolean {
        val token = readToken(intent) ?: return false
        val creator = try {
            token.creatorPackage
        } catch (e: Exception) {
            null
        }
        val trusted = isTrusted(creator, PINNED_SHELLY_CERT_SHA256) { pkg, cert ->
            packageManager.hasSigningCertificate(pkg, cert, PackageManager.CERT_INPUT_SHA256)
        }
        Log.i(TAG, "caller token present: creator=$creator trusted=$trusted")
        return trusted
    }

    private fun readToken(intent: Intent): PendingIntent? {
        if (!intent.hasExtra(EXTRA_CALLER_TOKEN)) return null
        return try {
            IntentCompat.getParcelableExtra(intent, EXTRA_CALLER_TOKEN, PendingIntent::class.java)
        } catch (e: Exception) {
            // Malformed / wrong-typed extras must fail closed, never crash the activity.
            Log.w(TAG, "unreadable caller token: ${e.javaClass.simpleName}")
            null
        }
    }

    internal fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.replace(":", "").trim()
        if (clean.length != 64) return null
        return try {
            ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
