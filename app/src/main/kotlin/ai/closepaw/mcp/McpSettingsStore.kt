package ai.closepaw.mcp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * MCP server settings. The on/off toggle (not a secret) lives in plain prefs; the bearer token
 * lives in its own [EncryptedSharedPreferences] file backed by an AES256 Android Keystore master key. The token is never logged.
 *
 * Disk + Keystore access: call off the main thread.
 */
class McpSettingsStore(
    private val context: Context,
    private val securePrefsProvider: (Context) -> SharedPreferences = ::defaultEncryptedPrefs,
) {
    private val plain: SharedPreferences
        get() = context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy { securePrefsProvider(context) }

    fun isEnabled(): Boolean = plain.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        plain.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The stored token, or null if none / malformed. */
    fun token(): String? = secure.getString(KEY_TOKEN, null)?.takeIf(McpAuth::isWellFormedToken)

    fun tokenOrCreate(): String = token() ?: regenerateToken()

    /** Replaces the token. The old one stops working as soon as the server reads the new value. */
    fun regenerateToken(): String {
        val token = McpAuth.generateToken()
        check(secure.edit().putString(KEY_TOKEN, token).commit()) { "failed to store MCP token" }
        return token
    }

    companion object {
        private const val PLAIN_PREFS = "mcp_server"
        private const val SECURE_PREFS = "mcp_server_secure"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TOKEN = "bearer_token"

        internal fun defaultEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
