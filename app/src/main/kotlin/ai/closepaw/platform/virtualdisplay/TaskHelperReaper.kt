package ai.closepaw.platform.virtualdisplay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App-lifetime owner for task helpers whose death or staging removal could not
 * be confirmed when their session closed. Enrollment transfers ownership: the
 * reaper keeps attempting retirement in the background (process cleanup kills
 * the helper pid-verified and removes staging; the connection is dropped only
 * once death has been genuinely observed) until both are settled. Nothing on
 * the platform lifecycle ever blocks on the reaper.
 */
internal object TaskHelperReaper {
    private const val TAG = "TaskHelperReaper"
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 30_000L

    private class Entry(
            val process: TaskHelperProcess?,
            val connection: TaskHelperSession.Connection?,
    ) {
        val directory: String get() = process?.directoryPath ?: "<connection-only>"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = LinkedHashSet<Entry>()
    private var loopActive = false

    fun enroll(process: TaskHelperProcess?, connection: TaskHelperSession.Connection?) {
        require(process != null || connection != null) { "Nothing to reap" }
        val entry = Entry(process, connection)
        synchronized(lock) {
            pending.add(entry)
            Log.w(TAG, "Reaping unconfirmed task helper (staging=${entry.directory}); pending=${pending.size}")
            if (!loopActive) {
                loopActive = true
                scope.launch { runLoop() }
            }
        }
    }

    /** Staging directories whose retirement is still unconfirmed (observability). */
    fun pendingDirectories(): List<String> = synchronized(lock) { pending.map { it.directory } }

    private suspend fun runLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            val snapshot = synchronized(lock) {
                if (pending.isEmpty()) {
                    loopActive = false
                    return
                }
                pending.toList()
            }
            var retiredAny = false
            for (entry in snapshot) {
                if (retire(entry)) {
                    synchronized(lock) { pending.remove(entry) }
                    Log.i(TAG, "Task helper retired (staging=${entry.directory})")
                    retiredAny = true
                }
            }
            if (retiredAny) backoffMs = INITIAL_BACKOFF_MS
            delay(backoffMs)
            if (!retiredAny) backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun retire(entry: Entry): Boolean {
        val process = entry.process
        if (process != null && process.pending) {
            // Kills the helper (pid-verified) and removes staging; throws while
            // either is unconfirmed, which simply schedules the next attempt.
            runCatching { process.cleanup() }
        }
        val deathSettled = entry.connection?.isDeathObserved() ?: true
        val stagingSettled = process == null || !process.pending
        if (deathSettled && stagingSettled) {
            entry.connection?.drop()
            return true
        }
        return false
    }
}
