package ai.closepaw.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Packages that are system surfaces rather than apps the agent operates: every installed home
 * launcher (e.g. Samsung One UI Home `com.sec.android.app.launcher`, which is not in the bundled
 * tier list and therefore CAUTIOUS) plus System UI. See [ai.closepaw.tool.PolicyEngine].
 */
internal object SystemSurfaces {
    private const val TAG = "SystemSurfaces"
    const val SYSTEM_UI = "com.android.systemui"

    fun resolve(context: Context): Set<String> {
        val homes = try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "home launcher lookup failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        // The Settings "fallback home" is not a launcher users interact with.
        val result = (homes - "com.android.settings").toSet() + SYSTEM_UI
        Log.d(TAG, "system surfaces: $result")
        return result
    }
}
