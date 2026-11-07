package ai.closepaw.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.closepaw.draft.SaveDraftRequestParser
import ai.closepaw.onboarding.OnboardingStore
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Non-UI entry for the fixed draft routine. Same proof of origin as [ShellyGoalReceiver]: a
 * [ShellyTrustedCaller] token plus, on API 34+, the broadcast's shared sender identity. The
 * request has no goal text: `platform`, the Substack subdomain and the file's SHA-256 are extras,
 * and the article is a `content://` file served by Shelly's own provider, read once here. Nothing
 * but the extras above is taken from the intent; anything else the run uses comes from the file.
 *
 * The ordered answer is given within [ShellySaveDraft.ANSWER_CAP_MS], read and parse included. A
 * watchdog on the main thread holds that cap even if the read blocks, and a late decision refuses.
 */
class ShellySaveDraftReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val verified = intent.action == ShellySaveDraft.ACTION_SAVE_DRAFT &&
            ShellyTrustedCaller.verify(intent, app.packageManager) &&
            ShellyTrustedCaller.isBroadcastSenderTrusted(
                sdkInt = Build.VERSION.SDK_INT,
                sentFromPackage = sentFromPackageSafe(),
                pinnedCertsSha256 = ShellyTrustedCaller.PINNED_SHELLY_CERT_SHA256,
            ) { pkg, cert ->
                app.packageManager.hasSigningCertificate(pkg, cert, PackageManager.CERT_INPUT_SHA256)
            }
        val target = ShellySaveDraft.parseTarget(
            action = intent.action,
            callerVerified = verified,
            platformExtra = safeString(intent, ShellySaveDraft.EXTRA_PLATFORM),
            subdomainExtra = safeString(intent, ShellySaveDraft.EXTRA_SUBSTACK_SUBDOMAIN),
            reqIdExtra = safeString(intent, ShellyBackgroundRun.EXTRA_SHELLY_REQ_ID),
            nonceExtra = safeString(intent, ShellyBackgroundRun.EXTRA_SHELLY_RESULT_NONCE),
            sha256Extra = safeString(intent, ShellySaveDraft.EXTRA_REQUEST_SHA256),
        )
        intent.removeExtra(ShellyTrustedCaller.EXTRA_CALLER_TOKEN)
        val uri = intent.data
        if (target == null || uri == null || !isShellyFile(app, uri)) {
            Log.w(TAG, "ignored broadcast: action=${intent.action} verified=$verified")
            reject(RESULT_DATA_REJECTED)
            return
        }

        val ordered = isOrderedBroadcast
        val pending = goAsync()
        val window = ShellySaveDraft.AnswerWindow(ShellySaveDraft.ANSWER_CAP_MS, SystemClock::elapsedRealtime)
        val respond = { decide: () -> Pair<Int, String> ->
            ShellySaveDraft.answer(
                decide = decide,
                reply = { code, data ->
                    // Tells Shelly the run will report through ShellyResultCallback.
                    val extras = if (code == ShellyBackgroundRun.RESULT_STARTED_VIRTUAL_DISPLAY) {
                        Bundle().apply { putInt(ShellyBackgroundRun.RESULT_EXTRA_RESULT_CALLBACK, 1) }
                    } else {
                        null
                    }
                    if (ordered) pending.setResult(code, data, extras)
                },
                finish = { pending.finish() },
            )
        }
        val tooSlow = { ShellyBackgroundRun.RESULT_REJECTED to ShellySaveDraft.REASON_TOO_SLOW }
        val watchdog = Runnable {
            if (window.claim() != ShellySaveDraft.AnswerWindow.Claim.ALREADY_ANSWERED) {
                Log.w(TAG, "request too slow; refused")
                respond(tooSlow)
            }
        }
        MAIN.postDelayed(watchdog, ShellySaveDraft.ANSWER_CAP_MS)
        WORKER.execute {
            // Disk, binder and provider work stays off the main thread. Anything thrown here,
            // errors included, ends in START_FAILED rather than a dead process or a broadcast
            // that never finishes.
            val checks = try {
                gather(app, target, uri, window)
            } catch (e: Throwable) {
                Log.e(TAG, "checks failed: ${e.javaClass.simpleName}")
                null
            }
            MAIN.post {
                when (window.claim()) {
                    ShellySaveDraft.AnswerWindow.Claim.ALREADY_ANSWERED -> Unit
                    ShellySaveDraft.AnswerWindow.Claim.LATE -> respond(tooSlow)
                    ShellySaveDraft.AnswerWindow.Claim.IN_TIME -> {
                        MAIN.removeCallbacks(watchdog)
                        respond { decideAndStart(target, checks!!) }
                    }
                }
            }
        }
    }

    private class Checks(
        val parsed: SaveDraftRequestParser.Parsed,
        val onboarded: Boolean,
        val virtualDisplayReady: Boolean,
        val locked: Boolean,
    )

    private fun gather(
        app: Context,
        target: ShellySaveDraft.Target,
        uri: Uri,
        window: ShellySaveDraft.AnswerWindow,
    ): Checks {
        val parsed = ShellySaveDraft.readRequest(target.site, target.requestSha256) {
            readFile(app.contentResolver, uri, window)
        }
        val virtualDisplayReady = try {
            ShizukuClient().let { it.isAvailable() && it.hasPermission() }
        } catch (e: Throwable) {
            false
        }
        return Checks(
            parsed = parsed,
            onboarded = OnboardingStore(app).isCompleted,
            virtualDisplayReady = virtualDisplayReady,
            locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
        )
    }

    /**
     * Main thread, where AgentService's busy state lives. Returns the ordered-broadcast code and
     * data. The data is a reason from a fixed set, never file content.
     */
    private fun decideAndStart(
        target: ShellySaveDraft.Target,
        checks: Checks,
    ): Pair<Int, String> {
        val service = AgentService.instance
        val refusal = ShellySaveDraft.refusal(
            accessibilityConnected = service != null,
            onboardingCompleted = checks.onboarded,
            sessionBusy = service?.isSessionBusy() == true,
            virtualDisplayReady = checks.virtualDisplayReady,
            deviceLocked = checks.locked,
            chromeInFront = service?.isChromeInFront() != false,
        )
        if (refusal != null) return refusal to "REFUSED"
        val parsed = checks.parsed
        if (parsed is SaveDraftRequestParser.Parsed.Bad) {
            Log.w(TAG, "refused file: ${parsed.reason}")
            return ShellyBackgroundRun.RESULT_REJECTED to parsed.reason
        }
        if (!REPLAY_GUARD.firstUse(target.correlation.reqId)) {
            Log.w(TAG, "refused a repeated request")
            return ShellyBackgroundRun.RESULT_REJECTED to "REPEATED_REQUEST"
        }
        service!!.startShellySaveDraft((parsed as SaveDraftRequestParser.Parsed.Ok).request, target.correlation)
        return ShellyBackgroundRun.RESULT_STARTED_VIRTUAL_DISPLAY to "STARTED_VIRTUAL_DISPLAY"
    }

    private fun reject(data: String) {
        if (isOrderedBroadcast) {
            resultCode = ShellyBackgroundRun.RESULT_REJECTED
            resultData = data
        }
    }

    /** A `content://` URI whose provider really is Shelly's, not one that merely claims to be. */
    private fun isShellyFile(app: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        return try {
            app.packageManager.resolveContentProvider(authority, 0)?.packageName ==
                ShellyTrustedCaller.SHELLY_PACKAGE
        } catch (e: Exception) {
            false
        }
    }

    private fun readFile(resolver: ContentResolver, uri: Uri, window: ShellySaveDraft.AnswerWindow): ByteArray =
        resolver.openInputStream(uri)?.use { input ->
            ShellySaveDraft.readLimited(input, SaveDraftRequestParser.MAX_FILE_BYTES, window::isExpired)
        } ?: throw IOException("no stream")

    private fun sentFromPackageSafe(): String? = try {
        if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
    } catch (e: Exception) {
        null
    }

    private fun safeString(intent: Intent, name: String): String? = try {
        intent.getStringExtra(name)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "ShellySaveDraftReceiver"
        const val RESULT_DATA_REJECTED = "REJECTED"
        // Cached, not single: a read stuck in a provider must not hold up the next request.
        val WORKER: ExecutorService = Executors.newCachedThreadPool()
        val MAIN = Handler(Looper.getMainLooper())
        val REPLAY_GUARD = ShellySaveDraft.ReplayGuard()
    }
}
