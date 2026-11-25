package ai.closepaw.app

import android.content.Context

/** Small per-app flags for the Shelly integration. */
internal object ShellyRunPrefs {
    private const val PREFS = "shelly_integration"
    private const val KEY_USED = "used_by_shelly"
    private const val KEY_NOTIF_ASKED = "notification_permission_asked"
    /** True once, the first time MainActivity should ask for POST_NOTIFICATIONS for Shelly runs. */
    fun shouldAskNotificationPermission(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_USED, false) || prefs.getBoolean(KEY_NOTIF_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        return true
    }
}
