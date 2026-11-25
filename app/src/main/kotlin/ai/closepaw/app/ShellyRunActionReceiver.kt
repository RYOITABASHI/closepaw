package ai.closepaw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
        }
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
