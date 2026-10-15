package ai.closepaw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import ai.closepaw.llm.ModelCatalogRepositoryHolder
import ai.closepaw.onboarding.OnboardingStore
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.protocol.PlatformMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Non-UI entry point for goals handed off by a verified Shelly caller.
 *
 * Exported so Shelly can reach it with an explicit broadcast, but it acts ONLY when the intent
 * carries a [ShellyTrustedCaller] token whose creator is dev.shelly.terminal with the pinned
 * signing cert AND (API 34+) the broadcast's shared sender identity is that same Shelly
 * ([ShellyTrustedCaller.isBroadcastSenderTrusted]). Anything else is ignored silently (no UI, no notification) apart from a
 * [ShellyBackgroundRun.RESULT_REJECTED] result code on ordered broadcasts.
 *
 * Why a receiver and not a (foreground) service: ClosePaw's process is already kept alive by its
 * bound AccessibilityService ([AgentService]), which owns the session scope. The receiver only
 * validates and hands off, so no FGS start (and none of the Android 12+ background-FGS-start
 * restrictions / Android 14 FGS-type rules) is involved, and no activity is started (no BAL).
 *
 * The run never opens MainActivity. It uses the Shizuku virtual display; if that is unavailable
 * it refuses with a notification, unless the caller sent [ShellyBackgroundRun.EXTRA_ALLOW_MAIN_DISPLAY]
 * AND the user enabled "Allow Shelly to control the main screen" in ClosePaw (default OFF).
 *
 * Note: a run replaces an idle user session (same as the FORCE_FRESH hand-off), never a busy
 * one; and it uses the user's approval mode, so with AUTO_APPROVE no per-app prompts appear.
 */
class ShellyGoalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val tokenVerified = intent.action == ShellyBackgroundRun.ACTION_RUN_GOAL &&
            ShellyTrustedCaller.verify(intent, app.packageManager)
        val senderPackage = sentFromPackageSafe()
        val senderVerified = ShellyTrustedCaller.isBroadcastSenderTrusted(
            sdkInt = Build.VERSION.SDK_INT,
            sentFromPackage = senderPackage,
            pinnedCertsSha256 = ShellyTrustedCaller.PINNED_SHELLY_CERT_SHA256,
        ) { pkg, cert ->
            app.packageManager.hasSigningCertificate(pkg, cert, PackageManager.CERT_INPUT_SHA256)
        }
        val request = ShellyBackgroundRun.parseRequest(
            action = intent.action,
            goalExtra = safeGoal(intent),
            callerVerified = tokenVerified && senderVerified,
            allowMainDisplay = intent.getBooleanExtra(ShellyBackgroundRun.EXTRA_ALLOW_MAIN_DISPLAY, false),
            reqIdExtra = safeString(intent, ShellyBackgroundRun.EXTRA_SHELLY_REQ_ID),
            nonceExtra = safeString(intent, ShellyBackgroundRun.EXTRA_SHELLY_RESULT_NONCE),
        )
        intent.removeExtra(ShellyTrustedCaller.EXTRA_CALLER_TOKEN)
        if (request == null) {
            Log.w(
                TAG,
                "ignored broadcast: action=${intent.action} token=$tokenVerified " +
                    "sender=$senderPackage senderVerified=$senderVerified",
            )
            if (isOrderedBroadcast) {
                resultCode = ShellyBackgroundRun.RESULT_REJECTED
                resultData = "REJECTED"
            }
            return
        }

        // Settings / credential / Shizuku probes touch disk and binders: do them off the main
        // thread, then decide + start on the main thread (where AgentService state lives, so two
        // back-to-back requests are serialized against AgentService's in-flight guard).
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        WORKER.execute {
            val probes = try {
                probe(app)
            } catch (e: Throwable) {
                Log.e(TAG, "probe failed", e)
                null
            }
            MAIN.post {
                try {
                    val (code, data) = if (probes == null) {
                        ShellyBackgroundRun.RESULT_REJECTED to "PROBE_FAILED"
                    } else {
                        decideAndStart(app, request, probes)
                    }
                    // Capability: tell Shelly this run WILL report its final state, so a
                    // Shelly talking to an older ClosePaw (no extra) never waits forever.
                    val extras = if (ShellyBackgroundRun.reportsResult(code, request.correlation)) {
                        android.os.Bundle().apply { putInt(ShellyBackgroundRun.RESULT_EXTRA_RESULT_CALLBACK, 1) }
                    } else {
                        null
                    }
                    if (ordered) pending.setResult(code, data, extras)
                } catch (e: Throwable) {
                    Log.e(TAG, "start failed", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private class Probes(
        val onboardingCompleted: Boolean,
        val canDrawOverlays: Boolean,
        val credentialsMissing: Boolean,
        val virtualDisplayReady: Boolean,
        val mainDisplayAllowedBySetting: Boolean,
    )

    private fun probe(app: Context): Probes {
        ShellyRunPrefs.markUsedByShelly(app)
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
        return Probes(
            onboardingCompleted = OnboardingStore(app).isCompleted,
            canDrawOverlays = Settings.canDrawOverlays(app),
            credentialsMissing = credentialsMissing,
            virtualDisplayReady = vdReady,
            mainDisplayAllowedBySetting = ShellyRunPrefs.isMainDisplayAllowed(app),
        )
    }

    /** Main thread. Returns the ordered-broadcast result (code, data). */
    private fun decideAndStart(
        app: Context,
        request: ShellyBackgroundRun.Request,
        probes: Probes,
    ): Pair<Int, String> {
        val service = AgentService.instance
        val decision = ShellyBackgroundRun.precheck(
            accessibilityConnected = service != null,
            onboardingCompleted = probes.onboardingCompleted,
            canDrawOverlays = probes.canDrawOverlays,
            credentialsMissing = probes.credentialsMissing,
            sessionBusy = service?.isSessionBusy() == true,
            virtualDisplayReady = probes.virtualDisplayReady,
            // The caller's opt-in only counts when the user enabled it in ClosePaw settings.
            allowMainDisplay = request.allowMainDisplay && probes.mainDisplayAllowedBySetting,
        )
        Log.i(TAG, "verified Shelly request: decision=$decision")
        return when (decision) {
            is ShellyBackgroundRun.Decision.Start -> {
                service!!.startShellyRun(request.goal, decision.platformMode, request.correlation)
                if (decision.platformMode == PlatformMode.VIRTUAL_DISPLAY) {
                    ShellyBackgroundRun.RESULT_STARTED_VIRTUAL_DISPLAY to "STARTED_VIRTUAL_DISPLAY"
                } else {
                    ShellyBackgroundRun.RESULT_STARTED_MAIN_DISPLAY to "STARTED_MAIN_DISPLAY"
                }
            }
            is ShellyBackgroundRun.Decision.Blocked -> {
                ShellyRunNotifier(app).showBlocked(decision.block, request.goal)
                decision.block.resultCode to decision.block.name
            }
        }
    }

    private fun sentFromPackageSafe(): String? = try {
        if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
    } catch (e: Exception) {
        null
    }

    private fun safeGoal(intent: Intent): String? = safeString(intent, ShellyBackgroundRun.EXTRA_GOAL)

    private fun safeString(intent: Intent, name: String): String? = try {
        intent.getStringExtra(name)
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "ShellyGoalReceiver"
        private val WORKER: ExecutorService = Executors.newSingleThreadExecutor()
        private val MAIN = Handler(Looper.getMainLooper())
    }
}

/** Small per-app flags for the Shelly integration. */
internal object ShellyRunPrefs {
    private const val PREFS = "shelly_integration"
    private const val KEY_USED = "used_by_shelly"
    private const val KEY_NOTIF_ASKED = "notification_permission_asked"
    private const val KEY_ALLOW_MAIN_DISPLAY = "allow_main_display"

    /** "Allow Shelly to control the main screen" (default OFF). */
    fun isMainDisplayAllowed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALLOW_MAIN_DISPLAY, false)

    fun setMainDisplayAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ALLOW_MAIN_DISPLAY, allowed).apply()
    }

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
