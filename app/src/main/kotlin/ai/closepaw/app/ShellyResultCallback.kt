package ai.closepaw.app

import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Reports the final state of a Shelly background run back to Shelly, so the caller that issued
 * `shelly closepaw` (e.g. Codex driven from the ChatGPT app) can learn what happened.
 *
 * Protocol (kept in lockstep with Shelly's ClosePawRunResults / ClosePawResultReceiver):
 *  - explicit broadcast to component [SHELLY_PACKAGE]/[SHELLY_RESULT_RECEIVER], action
 *    [ACTION_RESULT];
 *  - extras: [EXTRA_REQ_ID] + [EXTRA_RESULT_NONCE] (echoed from the goal broadcast, see
 *    [ShellyBackgroundRun.EXTRA_SHELLY_REQ_ID]), [EXTRA_STATUS] (completed | failed | cancelled |
 *    timeout), [EXTRA_SUMMARY] (the agent's final answer / error text, at most
 *    [MAX_SUMMARY_CODE_POINTS] code points, may be empty), [EXTRA_FINISHED_AT_MS],
 *    [EXTRA_SESSION_ID] (ClosePaw's session id, informational) and [EXTRA_CALLER_TOKEN] (an
 *    immutable PendingIntent created by ClosePaw, so Shelly can verify the creator package +
 *    signing cert on releases that cannot report the sender);
 *  - API 34+: sent with BroadcastOptions.setShareIdentityEnabled(true); Shelly requires it there.
 *
 * Sent ONLY when the installed dev.shelly.terminal is signed by the pinned Shelly certificate, so
 * the result text (which can contain what the agent saw on screen) never reaches an impersonator.
 * Fire-and-forget: a lost result only means the Shelly-side wait times out.
 */
object ShellyResultCallback {
    private const val TAG = "ShellyResultCallback"

    const val SHELLY_PACKAGE = ShellyTrustedCaller.SHELLY_PACKAGE
    const val SHELLY_RESULT_RECEIVER = "expo.modules.terminalemulator.ClosePawResultReceiver"
    const val ACTION_RESULT = "dev.shelly.terminal.action.CLOSEPAW_RESULT"
    const val EXTRA_REQ_ID = "shelly_req_id"
    const val EXTRA_RESULT_NONCE = "shelly_result_nonce"
    const val EXTRA_STATUS = "status"
    const val EXTRA_SUMMARY = "summary"
    const val EXTRA_FINISHED_AT_MS = "finished_at_ms"
    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_CALLER_TOKEN = "closepaw_caller_token"
    private const val TOKEN_ACTION = "ai.closepaw.action.SHELLY_RESULT_TOKEN"

    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_TIMEOUT = "timeout"

    const val MAX_SUMMARY_CODE_POINTS = 2_000

    data class Payload(
        val reqId: String,
        val nonce: String,
        val status: String,
        val summary: String,
        val finishedAtMs: Long,
        val sessionId: String?,
    )

    fun statusFor(kind: ShellyRunState.Finished.Kind): String = when (kind) {
        ShellyRunState.Finished.Kind.DONE -> STATUS_COMPLETED
        ShellyRunState.Finished.Kind.STOPPED -> STATUS_CANCELLED
        ShellyRunState.Finished.Kind.FAILED -> STATUS_FAILED
    }

    /** Trim + cap by code points (never splits a surrogate pair). */
    fun truncateSummary(text: String?): String {
        val t = text?.trim().orEmpty()
        if (t.codePointCount(0, t.length) <= MAX_SUMMARY_CODE_POINTS) return t
        val end = t.offsetByCodePoints(0, MAX_SUMMARY_CODE_POINTS - 1)
        return t.substring(0, end) + "…"
    }

    /** Payload for a finished tracker, or null if no result was requested / it is not finished. */
    fun payloadFor(tracker: ShellyRunTracker, nowMs: Long): Payload? {
        val correlation = tracker.correlation ?: return null
        val finished = tracker.state as? ShellyRunState.Finished ?: return null
        return Payload(
            reqId = correlation.reqId,
            nonce = correlation.nonce,
            status = statusFor(finished.kind),
            summary = truncateSummary(finished.message),
            finishedAtMs = nowMs,
            sessionId = tracker.sessionId,
        )
    }

    /** Payload for a run that ended before a tracker existed (start failure / refusal). */
    fun failurePayload(
        correlation: ShellyBackgroundRun.ResultCorrelation?,
        message: String?,
        nowMs: Long,
    ): Payload? {
        correlation ?: return null
        return Payload(correlation.reqId, correlation.nonce, STATUS_FAILED, truncateSummary(message), nowMs, null)
    }

    /** True when the installed Shelly is signed by a pinned certificate. */
    fun isShellyTrusted(packageManager: PackageManager): Boolean =
        ShellyTrustedCaller.isTrusted(SHELLY_PACKAGE, ShellyTrustedCaller.PINNED_SHELLY_CERT_SHA256) { pkg, cert ->
            packageManager.hasSigningCertificate(pkg, cert, PackageManager.CERT_INPUT_SHA256)
        }

    private fun buildIntent(context: Context, payload: Payload): Intent = Intent(ACTION_RESULT).apply {
        component = ComponentName(SHELLY_PACKAGE, SHELLY_RESULT_RECEIVER)
        putExtra(EXTRA_REQ_ID, payload.reqId)
        putExtra(EXTRA_RESULT_NONCE, payload.nonce)
        putExtra(EXTRA_STATUS, payload.status)
        putExtra(EXTRA_SUMMARY, payload.summary)
        putExtra(EXTRA_FINISHED_AT_MS, payload.finishedAtMs)
        payload.sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        putExtra(
            EXTRA_CALLER_TOKEN,
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(TOKEN_ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    }

    /** Never throws. */
    fun send(context: Context, payload: Payload?) {
        payload ?: return
        val app = context.applicationContext
        try {
            if (!isShellyTrusted(app.packageManager)) {
                Log.w(TAG, "Shelly is not installed with a pinned signature; result not sent")
                return
            }
            val intent = buildIntent(app, payload)
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = BroadcastOptions.makeBasic().apply { setShareIdentityEnabled(true) }.toBundle()
                app.sendBroadcast(intent, null, opts)
            } else {
                app.sendBroadcast(intent)
            }
            // Never log the summary (free text): status and length only.
            Log.i(TAG, "sent Shelly result req=${payload.reqId} status=${payload.status} len=${payload.summary.length}")
        } catch (e: Exception) {
            Log.w(TAG, "failed to send Shelly result: ${e.javaClass.simpleName}")
        }
    }
}
