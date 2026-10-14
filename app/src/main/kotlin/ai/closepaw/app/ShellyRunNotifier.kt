package ai.closepaw.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.closepaw.R

/**
 * Renders [ShellyRunState] as one ongoing notification (plus a Toast fallback for the states that
 * need the user's attention when notifications are blocked). The Smart Capsule overlay keeps
 * working as usual; this notification is the always-available, text-explicit indicator of
 * "may I use my phone right now?".
 *
 * Colors / icons per state:
 * - working on the virtual display: green, paw icon, silent ongoing ("you can keep using your phone")
 * - controlling the main display: red, warning icon, heads-up ongoing ("please don't touch")
 * - waiting for approval: amber, alert icon, heads-up with Always / This time / Reject
 * - paused (user opened the operated app): amber, pause icon, Resume / Stop
 * - done / stopped / failed: blue / grey / red, auto-dismissed after [FINISHED_TIMEOUT_MS]
 */
class ShellyRunNotifier(context: Context) {
    private val context = context.applicationContext
    private val text = ShellyRunText(isJapanese(this.context))
    private var lastToastKey: String? = null

    companion object {
        private const val TAG = "ShellyRunNotifier"
        const val CHANNEL_STATUS = "shelly_run_status"
        const val CHANNEL_ATTENTION = "shelly_run_attention"
        const val NOTIFICATION_ID = 0x5E11
        private const val FINISHED_TIMEOUT_MS = 8_000L

        private const val COLOR_WORKING = 0xFF2E7D32.toInt()
        private const val COLOR_MAIN_DISPLAY = 0xFFC62828.toInt()
        private const val COLOR_ATTENTION = 0xFFF9A825.toInt()
        private const val COLOR_DONE = 0xFF1565C0.toInt()
        private const val COLOR_STOPPED = 0xFF757575.toInt()

        fun isJapanese(context: Context): Boolean =
            context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)?.language == "ja"
    }

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, text.channelStatus, NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, text.channelAttention, NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun canNotify(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun labelOf(pkg: String): String = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    fun render(state: ShellyRunState) {
        val content = text.render(state, ::labelOf)
        val attention = when (state) {
            is ShellyRunState.Working -> state.onMainDisplay
            is ShellyRunState.WaitingForApproval, is ShellyRunState.Paused -> true
            is ShellyRunState.Finished -> false
        }
        if (!canNotify()) {
            // Toast fallback (notifications blocked): one toast per distinct state, so the user
            // still learns whether they may use the phone, must approve, or the run ended.
            val key = toastKey(state)
            if (key != lastToastKey) {
                lastToastKey = key
                Toast.makeText(context, "${content.title}\n${content.body}".trim(), Toast.LENGTH_LONG).show()
            }
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)

        val builder = NotificationCompat.Builder(context, if (attention) CHANNEL_ATTENTION else CHANNEL_STATUS)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(state is ShellyRunState.Working && !state.onMainDisplay)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(if (attention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)

        when (state) {
            is ShellyRunState.Working -> {
                builder.setOngoing(true)
                    .setSmallIcon(if (state.onMainDisplay) android.R.drawable.stat_sys_warning else R.drawable.ic_paw)
                    .setColor(if (state.onMainDisplay) COLOR_MAIN_DISPLAY else COLOR_WORKING)
                    .setProgress(0, 0, true)
                    .addAction(0, text.actionStop, actionIntent(ShellyRunActionReceiver.ACTION_STOP))
                if (state.onMainDisplay) builder.setCategory(NotificationCompat.CATEGORY_STATUS)
            }
            is ShellyRunState.WaitingForApproval -> {
                builder.setOngoing(true)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setColor(COLOR_ATTENTION)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .addAction(securedAction(text.actionAllowAlways, approvalIntent(ShellyRunActionReceiver.ACTION_ALLOW_ALWAYS, state)))
                    .addAction(securedAction(text.actionAllowSession, approvalIntent(ShellyRunActionReceiver.ACTION_ALLOW_SESSION, state)))
                    .addAction(0, text.actionReject, approvalIntent(ShellyRunActionReceiver.ACTION_REJECT, state))
            }
            is ShellyRunState.Paused -> {
                builder.setOngoing(true)
                    .setSmallIcon(android.R.drawable.ic_media_pause)
                    .setColor(COLOR_ATTENTION)
                    .addAction(securedAction(text.actionResume, actionIntent(ShellyRunActionReceiver.ACTION_RESUME)))
                    .addAction(0, text.actionStop, actionIntent(ShellyRunActionReceiver.ACTION_STOP))
            }
            is ShellyRunState.Finished -> {
                val (icon, color) = when (state.kind) {
                    ShellyRunState.Finished.Kind.DONE -> android.R.drawable.ic_dialog_info to COLOR_DONE
                    ShellyRunState.Finished.Kind.STOPPED -> android.R.drawable.ic_menu_close_clear_cancel to COLOR_STOPPED
                    ShellyRunState.Finished.Kind.FAILED -> android.R.drawable.stat_notify_error to COLOR_MAIN_DISPLAY
                }
                builder.setOngoing(false)
                    .setAutoCancel(true)
                    .setSmallIcon(icon)
                    .setColor(color)
                    .setTimeoutAfter(FINISHED_TIMEOUT_MS)
            }
        }
        post(nm, builder)
    }

    /** A request was refused before anything started. Tap opens ClosePaw (never auto-opens). */
    fun showBlocked(block: ShellyBackgroundRun.Block, goal: String?) {
        val content = text.blocked(block)
        if (!canNotify()) {
            Toast.makeText(context, "${content.title}\n${content.body}", Toast.LENGTH_LONG).show()
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(nm)
        // Only the VD-unavailable case offers "run in the foreground": the tap opens MainActivity
        // with the goal WITHOUT a caller token, so the normal External Goal confirmation applies.
        val tap = if (block == ShellyBackgroundRun.Block.VIRTUAL_DISPLAY_UNAVAILABLE && goal != null) {
            openAppIntent(goal)
        } else {
            openAppIntent()
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setColor(COLOR_ATTENTION)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
        post(nm, builder)
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Actions that grant or continue agent control (Allow / Resume) require the device to be
     * unlocked when tapped from the lock screen. Stop and Reject only reduce what the agent may
     * do, so they stay usable without unlocking.
     */
    private fun securedAction(title: String, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, title, intent)
            .setAuthenticationRequired(true)
            .build()

    private fun post(nm: NotificationManager, builder: NotificationCompat.Builder) {
        // No goal / result / app names / approval text on the lock screen.
        val channel = builder.build().channelId ?: CHANNEL_STATUS
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("ClosePaw")
            .setContentText(text.lockScreenSummary)
            .build()
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification not posted: ${e.message}")
        }
    }

    private fun toastKey(state: ShellyRunState): String = when (state) {
        is ShellyRunState.Working -> "working:${state.onMainDisplay}"
        is ShellyRunState.WaitingForApproval -> "approval:${state.callId}"
        is ShellyRunState.Paused -> "paused:${state.packageName}"
        is ShellyRunState.Finished -> "finished:${state.kind}"
    }

    private fun openAppIntent(goal: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (goal != null) putExtra(MainActivity.EXTRA_GOAL, goal)
        }
        return PendingIntent.getActivity(
            context,
            if (goal != null) 2 else 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(
        action: String,
        requestCode: Int = action.hashCode(),
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, ShellyRunActionReceiver::class.java).setAction(action).apply(extras)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun approvalIntent(action: String, state: ShellyRunState.WaitingForApproval): PendingIntent =
        actionIntent(action, requestCode = (action + state.callId).hashCode()) {
            putExtra(ShellyRunActionReceiver.EXTRA_CALL_ID, state.callId)
            putExtra(ShellyRunActionReceiver.EXTRA_PACKAGE, state.packageName)
        }
}
