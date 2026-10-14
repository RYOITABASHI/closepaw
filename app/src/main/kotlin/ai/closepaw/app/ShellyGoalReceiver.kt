package ai.closepaw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import ai.closepaw.llm.ModelCatalogRepositoryHolder
import ai.closepaw.onboarding.OnboardingStore
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.protocol.PlatformMode

/**
 * Non-UI entry point for goals handed off by a verified Shelly caller.
 *
 * Exported so Shelly can reach it with an explicit broadcast, but it acts ONLY when the intent
 * carries a [ShellyTrustedCaller] token whose creator is dev.shelly.terminal with the pinned
 * signing cert. Anything else is ignored silently (no UI, no notification) apart from a
 * [ShellyBackgroundRun.RESULT_REJECTED] result code on ordered broadcasts.
 *
 * Why a receiver and not a (foreground) service: ClosePaw's process is already kept alive by its
 * bound AccessibilityService ([AgentService]), which owns the session scope. The receiver only
 * validates and hands off, so no FGS start (and none of the Android 12+ background-FGS-start
 * restrictions / Android 14 FGS-type rules) is involved, and no activity is started (no BAL).
 *
 * The run never opens MainActivity. It uses the Shizuku virtual display; if that is unavailable
 * it refuses with a notification, unless the caller opted into [ShellyBackgroundRun.EXTRA_ALLOW_MAIN_DISPLAY].
 */
class ShellyGoalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val verified = intent.action == ShellyBackgroundRun.ACTION_RUN_GOAL &&
            ShellyTrustedCaller.verify(intent, context.packageManager)
        val request = ShellyBackgroundRun.parseRequest(
            action = intent.action,
            goalExtra = safeGoal(intent),
            callerVerified = verified,
            allowMainDisplay = intent.getBooleanExtra(ShellyBackgroundRun.EXTRA_ALLOW_MAIN_DISPLAY, false),
        )
        intent.removeExtra(ShellyTrustedCaller.EXTRA_CALLER_TOKEN)
        if (request == null) {
            Log.w(TAG, "ignored broadcast: action=${intent.action} verified=$verified")
            reply(ShellyBackgroundRun.RESULT_REJECTED, "REJECTED")
            return
        }

        val app = context.applicationContext
        ShellyRunPrefs.markUsedByShelly(app)
        val service = AgentService.instance
        val settings = AppSettingsState.create(app).also { it.load() }
        val credentialsMissing = try {
            findMissingCloudKeys(
                settings,
                ModelCatalogRepositoryHolder.get(app).catalog.value,
                AuthStoreHolder.get(app),
            ).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "credential check failed: ${e.javaClass.simpleName}")
            true
        }
        val vdReady = try {
            ShizukuClient().let { it.isAvailable() && it.hasPermission() }
        } catch (e: Throwable) {
            false
        }
        val decision = ShellyBackgroundRun.precheck(
            accessibilityConnected = service != null,
            onboardingCompleted = OnboardingStore(app).isCompleted,
            canDrawOverlays = Settings.canDrawOverlays(app),
            credentialsMissing = credentialsMissing,
            sessionBusy = service?.isSessionBusy() == true,
            virtualDisplayReady = vdReady,
            allowMainDisplay = request.allowMainDisplay,
        )
        Log.i(TAG, "verified Shelly request: decision=$decision")
        when (decision) {
            is ShellyBackgroundRun.Decision.Start -> {
                service!!.startShellyRun(request.goal, decision.platformMode)
                if (decision.platformMode == PlatformMode.VIRTUAL_DISPLAY) {
                    reply(ShellyBackgroundRun.RESULT_STARTED_VIRTUAL_DISPLAY, "STARTED_VIRTUAL_DISPLAY")
                } else {
                    reply(ShellyBackgroundRun.RESULT_STARTED_MAIN_DISPLAY, "STARTED_MAIN_DISPLAY")
                }
            }
            is ShellyBackgroundRun.Decision.Blocked -> {
                ShellyRunNotifier(app).showBlocked(decision.block, request.goal)
                reply(decision.block.resultCode, decision.block.name)
            }
        }
    }

    private fun safeGoal(intent: Intent): String? = try {
        intent.getStringExtra(ShellyBackgroundRun.EXTRA_GOAL)
    } catch (e: Exception) {
        null
    }

    private fun reply(code: Int, data: String) {
        if (isOrderedBroadcast) {
            resultCode = code
            resultData = data
        }
    }

    companion object {
        private const val TAG = "ShellyGoalReceiver"
    }
}

/** Small per-app flags for the Shelly integration. */
internal object ShellyRunPrefs {
    private const val PREFS = "shelly_integration"
    private const val KEY_USED = "used_by_shelly"
    private const val KEY_NOTIF_ASKED = "notification_permission_asked"

    fun markUsedByShelly(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_USED, true).apply()
    }

    /** True once, the first time MainActivity should ask for POST_NOTIFICATIONS for Shelly runs. */
    fun shouldAskNotificationPermission(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_USED, false) || prefs.getBoolean(KEY_NOTIF_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        return true
    }
}
