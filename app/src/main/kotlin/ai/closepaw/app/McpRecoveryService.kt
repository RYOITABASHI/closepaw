package ai.closepaw.app

import ai.closepaw.mcp.McpSettingsStore
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class McpRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        if (McpRecoveryService.ensureStarted(app, action, refresh = true)) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                McpServerController.sync(app)
                McpServerController.checkRecovery()
            } catch (e: Exception) {
                Log.w("ClosePawMcpAudit", "MCP broadcast recovery failed ($action)", e)
            } finally {
                pending.finish()
            }
        }
    }
}

class McpRecoveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!McpSettingsStore(this).isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = checkNotNull(getSystemService(NotificationManager::class.java))
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "MCP service", NotificationManager.IMPORTANCE_LOW))
            startForeground(7414, Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ClosePaw MCP")
                .setContentText("MCP listener and recovery are enabled")
                .setOngoing(true)
                .build())
            startFailure = null
        } catch (e: RuntimeException) {
            startFailure = e.javaClass.simpleName
            Log.w(TAG, "MCP foreground promotion failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        resident = true
        Log.i(TAG, "MCP resident start: ${intent?.action ?: "sticky_restart"}")
        if (watchdog?.isActive != true) {
            watchdog = scope.launch {
                while (true) {
                    try {
                        McpServerController.sync(applicationContext)
                        McpServerController.checkRecovery()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "MCP resident check failed", e)
                    }
                    delay(CHECK_INTERVAL_MS)
                }
            }
        } else {
            scope.launch { McpServerController.checkRecovery() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        resident = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ClosePawMcpAudit"
        private const val CHANNEL = "closepaw_mcp_residency"
        internal const val CHECK_INTERVAL_MS = 30_000L

        @Volatile internal var resident = false
            private set
        @Volatile internal var startFailure: String? = null
            private set

        internal fun ensureStarted(context: Context, reason: String, refresh: Boolean = false): Boolean {
            if ((resident && !refresh) || !McpSettingsStore(context).isEnabled()) return true
            return try {
                context.startForegroundService(Intent(context, McpRecoveryService::class.java).setAction(reason))
                true
            } catch (e: RuntimeException) {
                startFailure = e.javaClass.simpleName
                Log.w(TAG, "MCP resident start failed ($reason)", e)
                false
            }
        }
    }
}
