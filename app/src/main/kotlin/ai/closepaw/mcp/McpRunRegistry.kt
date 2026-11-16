package ai.closepaw.mcp

import java.security.SecureRandom

/**
 * Run handles issued to MCP clients by `run_goal`, and the state `get_run_result` reads.
 *
 * Lifecycle: [issue] (starting) -> [markRunning] -> exactly one terminal state via [complete]
 * (completed | failed | cancelled; the FIRST terminal report wins, later ones are ignored, the
 * same report-once rule as ShellyPendingResult). [await] long-polls for a terminal state up to
 * [MAX_WAIT_MS]. Bounded: only the newest [maxEntries] handles are kept (finished ones are evicted
 * first), so an unknown handle simply reads as "unknown".
 *
 * Thread-safe (HTTP worker threads poll, the main thread completes). Pure JVM.
 */
class McpRunRegistry(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val newHandle: () -> String = ::randomHandle,
) {
    enum class Status(val wire: String, val terminal: Boolean) {
        STARTING("starting", false),
        RUNNING("running", false),
        COMPLETED("completed", true),
        FAILED("failed", true),
        CANCELLED("cancelled", true),
    }

    data class Snapshot(val runHandle: String, val status: Status, val summary: String)

    private class Entry(val handle: String) {
        var status: Status = Status.STARTING
        var summary: String = ""
    }

    private val lock = Object()
    private val entries = LinkedHashMap<String, Entry>()

    fun issue(): String = synchronized(lock) {
        var handle = newHandle()
        while (entries.containsKey(handle)) handle = newHandle()
        entries[handle] = Entry(handle)
        evict()
        handle
    }

    /** starting -> running. False if unknown or already past starting (e.g. failed synchronously). */
    fun markRunning(handle: String): Boolean = synchronized(lock) {
        val e = entries[handle] ?: return false
        if (e.status != Status.STARTING) return false
        e.status = Status.RUNNING
        true
    }

    /** First terminal report wins. Returns true if this call set the terminal state. */
    fun complete(handle: String, status: Status, summary: String): Boolean = synchronized(lock) {
        require(status.terminal) { "not a terminal status: $status" }
        val e = entries[handle] ?: return false
        if (e.status.terminal) return false
        e.status = status
        e.summary = summary
        lock.notifyAll()
        true
    }

    fun snapshot(handle: String): Snapshot? = synchronized(lock) { entries[handle]?.toSnapshot() }

    /**
     * Current state, waiting up to [waitMs] (capped to [MAX_WAIT_MS]) for a terminal one.
     * Returns null for an unknown handle.
     */
    fun await(handle: String, waitMs: Long): Snapshot? {
        val deadline = System.nanoTime() + waitMs.coerceIn(0L, MAX_WAIT_MS) * 1_000_000L
        return synchronized(lock) { awaitLocked(handle, deadline) }
    }

    private fun awaitLocked(handle: String, deadlineNanos: Long): Snapshot? {
        while (true) {
            val e = entries[handle] ?: return null
            if (e.status.terminal) return e.toSnapshot()
            val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) return e.toSnapshot()
            lock.wait(remainingMs)
        }
    }

    fun activeCount(): Int = synchronized(lock) { entries.values.count { !it.status.terminal } }

    private fun evict() {
        while (entries.size > maxEntries) {
            val victim = entries.values.firstOrNull { it.status.terminal } ?: entries.values.first()
            entries.remove(victim.handle)
        }
    }

    private fun Entry.toSnapshot() = Snapshot(handle, status, summary)

    companion object {
        const val MAX_WAIT_MS = 20_000L
        const val DEFAULT_MAX_ENTRIES = 32

        private val RANDOM = SecureRandom()

        fun randomHandle(): String {
            val bytes = ByteArray(12).also(RANDOM::nextBytes)
            return "run_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
