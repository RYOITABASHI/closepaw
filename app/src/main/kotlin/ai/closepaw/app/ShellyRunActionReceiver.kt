package ai.closepaw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalScope

/**
 * Handles the action buttons of the Shelly run notification. NOT exported: only reachable through
 * the immutable PendingIntents ClosePaw itself puts on that notification.
 */
class ShellyRunActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = AgentService.instance
        if (service == null) {
            Log.w(TAG, "no AgentService for ${intent.action}")
            if (intent.action != ACTION_STOP) ShellyRunNotifier(context).cancel()
            return
        }
        when (intent.action) {
            ACTION_STOP -> intent.getStringExtra(EXTRA_STOP_IDENTITY)?.let(service::stopShellyRun)
            ACTION_RESUME -> service.resumeShellyRun()
            ACTION_ALLOW_ALWAYS -> approve(service, intent, ApprovalDecision.APPROVED, ApprovalScope.ALWAYS)
            ACTION_ALLOW_SESSION -> approve(service, intent, ApprovalDecision.APPROVED, ApprovalScope.SESSION)
            ACTION_REJECT -> approve(service, intent, ApprovalDecision.DENIED, ApprovalScope.SESSION)
        }
    }

    private fun approve(service: AgentService, intent: Intent, decision: ApprovalDecision, scope: ApprovalScope) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        service.resolveShellyApproval(sessionId, callId, decision, scope, pkg)
    }

    companion object {
        private const val TAG = "ShellyRunAction"
        const val ACTION_STOP = "ai.closepaw.shelly.STOP"
        const val ACTION_RESUME = "ai.closepaw.shelly.RESUME"
        const val ACTION_ALLOW_ALWAYS = "ai.closepaw.shelly.ALLOW_ALWAYS"
        const val ACTION_ALLOW_SESSION = "ai.closepaw.shelly.ALLOW_SESSION"
        const val ACTION_REJECT = "ai.closepaw.shelly.REJECT"
        const val EXTRA_STOP_IDENTITY = "stop_identity"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PACKAGE = "package_name"
    }
}
