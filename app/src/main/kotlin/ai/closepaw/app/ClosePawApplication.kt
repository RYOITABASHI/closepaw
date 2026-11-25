package ai.closepaw.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ClosePawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            discardRetiredCodexCredential(applicationContext)
        }
    }
}

private fun discardRetiredCodexCredential(context: Context) {
    try {
        val prefs = openExistingAuthStore(context) ?: return
        if (removeRetiredCodexCredential(prefs)) {
            Log.i("CredentialMigration", "Discarded retired Codex credential")
        }
    } catch (e: Exception) {
        Log.w("CredentialMigration", "Retired Codex credential removal failed; will retry on process restart")
    }
}

internal fun openExistingAuthStore(context: Context): SharedPreferences? {
    val prefsFile = File(context.dataDir, "shared_prefs/auth_store.xml")
    if (!prefsFile.exists() && !File(prefsFile.path + ".bak").exists()) return null

    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "auth_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

internal fun hasAppLegacyUsageEvidence(
    context: Context,
    openAuthStore: (Context) -> SharedPreferences?,
): Boolean {
    val settings = AppSettingsStore(context)
    if (settings.hasLegacyPlannerSettings() || settings.loadUserAppOverrides().isNotEmpty() ||
        File(context.filesDir, "sessions").listFiles()?.isNotEmpty() == true
    ) return true

    val prefs = try {
        openAuthStore(context)
    } catch (e: Exception) {
        Log.w("CredentialMigration", "Could not read legacy onboarding evidence")
        null
    } ?: return false
    // These two legacy providers stored API keys. Codex is retired and wiped independently.
    return listOf("OPENAI_API", "OPENROUTER").any { provider ->
        try {
            val raw = prefs.getString(provider, null) ?: return@any false
            val credential = Json.parseToJsonElement(raw) as? JsonObject ?: return@any false
            credential["type"] == JsonPrimitive("api_key") &&
                (credential["key"] as? JsonPrimitive)?.isString == true
        } catch (e: Exception) {
            false
        }
    }
}

internal fun removeRetiredCodexCredential(prefs: SharedPreferences): Boolean {
    val key = "OPENAI_CODEX"
    if (!prefs.contains(key)) return false
    check(prefs.edit().remove(key).commit())
    return true
}
