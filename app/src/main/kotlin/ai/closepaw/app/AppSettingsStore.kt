package ai.closepaw.app

import android.content.Context
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.PlatformMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
        val debugMode: Boolean,
        val perceptionMode: String,
        val platformMode: PlatformMode,
        val traceEnabled: Boolean,
        val browserScriptEnabled: Boolean,
        val termuxShellEnabled: Boolean,
        val approvalMode: ApprovalMode,
)

class AppSettingsStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "agent_prefs"

        private const val KEY_MODEL = "model"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_SCREENSHOT_INPUT = "screenshot_input"
        private const val KEY_PERCEPTION_MODE = "perception_mode"
        private const val KEY_LLM_BACKEND = "llm_backend"
        private const val KEY_PLATFORM_MODE = "platform_mode"
        private const val KEY_USER_APP_OVERRIDES = "user_app_overrides"
        private const val KEY_TRACE_ENABLED = "trace_enabled"
        private const val KEY_BROWSER_SCRIPT_ENABLED = "browser_script_enabled"
        private const val KEY_TERMUX_SHELL_ENABLED = "termux_shell_enabled"
        private const val KEY_DISABLED_AGENT_SKILLS = "disabled_agent_skills"
        private const val KEY_APPROVAL_MODE = "approval_mode"

        const val DEFAULT_DEBUG_MODE = false
        const val DEFAULT_PERCEPTION_MODE = "accessibility_only"
        val DEFAULT_PLATFORM_MODE = PlatformMode.ACCESSIBILITY
        const val DEFAULT_TRACE_ENABLED = false
        const val DEFAULT_BROWSER_SCRIPT_ENABLED = false
        const val DEFAULT_TERMUX_SHELL_ENABLED = true
        val DEFAULT_APPROVAL_MODE = ApprovalMode.SMART
    }

    private val _termuxShellEnabled = MutableStateFlow(loadTermuxShellEnabled())
    val termuxShellEnabled: StateFlow<Boolean> = _termuxShellEnabled.asStateFlow()

    private val _browserScriptEnabled = MutableStateFlow(loadBrowserScriptEnabled())
    val browserScriptEnabled: StateFlow<Boolean> = _browserScriptEnabled.asStateFlow()

    private val _disabledAgentSkills = MutableStateFlow(loadDisabledAgentSkills())
    val disabledAgentSkills: StateFlow<Set<String>> = _disabledAgentSkills.asStateFlow()

    // Serializes setSkillDisabled so concurrent toggles from the UI cannot lose entries
    // via the read-modify-write between _disabledAgentSkills.value and the prefs commit.
    private val disabledSkillsMutex = Mutex()

    /** Plain prefs for non-secret settings only (model, turns, mode, etc.). */
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Used only to recognize upgrades from before permission onboarding was introduced.
    internal fun hasLegacyPlannerSettings(): Boolean {
        val prefs = prefs()
        return prefs.getString(KEY_MODEL, "glm-5") != "glm-5" ||
            prefs.getString(KEY_LLM_BACKEND, "OPENAI") != "OPENAI"
    }

    fun load(): AppSettings {
        val prefs = prefs()

        val debugMode = prefs.getBoolean(KEY_DEBUG_MODE, DEFAULT_DEBUG_MODE)
        val perceptionMode =
                prefs.getString(KEY_PERCEPTION_MODE, null)
                        ?: if (prefs.getBoolean(KEY_SCREENSHOT_INPUT, false)) "hybrid"
                        else DEFAULT_PERCEPTION_MODE

        val platformModeName = prefs.getString(KEY_PLATFORM_MODE, DEFAULT_PLATFORM_MODE.name)
                ?: DEFAULT_PLATFORM_MODE.name
        val platformMode = try {
            PlatformMode.valueOf(platformModeName)
        } catch (_: Exception) {
            DEFAULT_PLATFORM_MODE
        }
        val traceEnabled = prefs.getBoolean(KEY_TRACE_ENABLED, DEFAULT_TRACE_ENABLED)
        val browserScriptEnabled =
                prefs.getBoolean(KEY_BROWSER_SCRIPT_ENABLED, DEFAULT_BROWSER_SCRIPT_ENABLED)
        val termuxShellEnabled = prefs.getBoolean(
                KEY_TERMUX_SHELL_ENABLED,
                DEFAULT_TERMUX_SHELL_ENABLED
        )
        val approvalModeName = prefs.getString(KEY_APPROVAL_MODE, DEFAULT_APPROVAL_MODE.name)
                ?: DEFAULT_APPROVAL_MODE.name
        val approvalMode = try {
            val parsed = ApprovalMode.valueOf(approvalModeName)
            if (parsed == ApprovalMode.ALWAYS_ASK) DEFAULT_APPROVAL_MODE else parsed
        } catch (_: Exception) {
            DEFAULT_APPROVAL_MODE
        }

        return AppSettings(
                debugMode = debugMode,
                perceptionMode = perceptionMode,
                platformMode = platformMode,
                traceEnabled = traceEnabled,
                browserScriptEnabled = browserScriptEnabled,
                termuxShellEnabled = termuxShellEnabled,
                approvalMode = approvalMode,
        )
    }

    fun loadTermuxShellEnabled(): Boolean =
        prefs().getBoolean(KEY_TERMUX_SHELL_ENABLED, DEFAULT_TERMUX_SHELL_ENABLED)

    suspend fun setTermuxShellEnabled(value: Boolean) {
        withContext(Dispatchers.IO) {
            prefs().edit().putBoolean(KEY_TERMUX_SHELL_ENABLED, value).apply()
        }
        _termuxShellEnabled.value = value
    }

    fun loadBrowserScriptEnabled(): Boolean =
        prefs().getBoolean(KEY_BROWSER_SCRIPT_ENABLED, DEFAULT_BROWSER_SCRIPT_ENABLED)

    suspend fun setBrowserScriptEnabled(value: Boolean) {
        withContext(Dispatchers.IO) {
            prefs().edit().putBoolean(KEY_BROWSER_SCRIPT_ENABLED, value).apply()
        }
        _browserScriptEnabled.value = value
    }

    fun saveDebugMode(value: Boolean) {
        prefs().edit().putBoolean(KEY_DEBUG_MODE, value).apply()
    }

    fun saveTraceEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_TRACE_ENABLED, value).apply()
    }

    fun saveBrowserScriptEnabled(value: Boolean) {
        prefs().edit().putBoolean(KEY_BROWSER_SCRIPT_ENABLED, value).apply()
        _browserScriptEnabled.value = value
    }

    fun savePerceptionMode(value: String) {
        prefs().edit().putString(KEY_PERCEPTION_MODE, value).apply()
    }

    fun savePlatformMode(value: PlatformMode) {
        prefs().edit().putString(KEY_PLATFORM_MODE, value.name).apply()
    }

    fun saveApprovalMode(value: ApprovalMode) {
        prefs().edit().putString(KEY_APPROVAL_MODE, value.name).apply()
    }

    // ===== User app overrides =====

    fun loadUserAppOverrides(): Map<String, AppTier> {
        val raw = prefs().getString(KEY_USER_APP_OVERRIDES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                for (key in obj.keys()) {
                    val tier = AppTier.fromString(obj.optString(key))
                    if (tier != null) put(key, tier)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Persist user app overrides synchronously via `commit()` on [Dispatchers.IO].
     *
     * Suspends until XML is fully written so [AppClassifier.setOverride] can `await` the
     * disk write under its mutex — guaranteeing last-emitted == last-persisted even under
     * concurrent writes. `apply()` would defer the write asynchronously and break that.
     */
    suspend fun saveUserAppOverrides(overrides: Map<String, AppTier>) {
        withContext(Dispatchers.IO) {
            val editor = prefs().edit()
            if (overrides.isEmpty()) {
                editor.remove(KEY_USER_APP_OVERRIDES).commit()
                return@withContext
            }
            val obj = JSONObject()
            for ((pkg, tier) in overrides) obj.put(pkg, tier.name)
            editor.putString(KEY_USER_APP_OVERRIDES, obj.toString()).commit()
        }
    }

    // ===== Disabled agent skills =====

    fun loadDisabledAgentSkills(): Set<String> {
        val raw = prefs().getString(KEY_DISABLED_AGENT_SKILLS, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i)
                    if (name.isNotEmpty()) add(name)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun setSkillDisabled(name: String, disabled: Boolean) = disabledSkillsMutex.withLock {
        val current = _disabledAgentSkills.value
        val next = if (disabled) current + name else current - name
        if (next == current) return@withLock
        withContext(Dispatchers.IO) {
            val editor = prefs().edit()
            if (next.isEmpty()) {
                editor.remove(KEY_DISABLED_AGENT_SKILLS).apply()
            } else {
                val arr = JSONArray()
                next.forEach { arr.put(it) }
                editor.putString(KEY_DISABLED_AGENT_SKILLS, arr.toString()).apply()
            }
        }
        _disabledAgentSkills.value = next
    }
}
