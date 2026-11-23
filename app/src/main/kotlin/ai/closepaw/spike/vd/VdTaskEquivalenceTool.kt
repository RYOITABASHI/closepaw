package ai.closepaw.spike.vd

import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.mcp.McpVdTaskEquivalence
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/**
 * Stage-2 Increment 1 spike surface: owns one helper session and runs the
 * paired reads of [VdTaskEquivalence] against it, per
 * stage2-increment1-experiment-plan.md section 3. The rendered
 * one-line-per-read log (the format the diff report is assembled from)
 * accumulates here and is mirrored into closepaw_status under
 * `spikeVdTasks`; each read call also returns its own lines, so the log
 * survives in the executor's transcript even if the app process dies.
 * Observation only -- this class never moves, removes, or starts a task.
 *
 * Calls are blocking (the MCP handler invokes tools on HTTP worker
 * threads). [operations] serializes session operations and batches. [lock]
 * protects journal and state snapshots, including while an operation waits.
 */
internal class VdTaskEquivalenceTool(context: Context, private val shell: AdbShellChannel) : McpVdTaskEquivalence {
    private val app = context.applicationContext
    private val session = VdHelperSession(app, shell)
    private val driver = VdTaskEquivalence(session, ShizukuClient())
    private val operations = Mutex()
    private val lock = Any()
    private enum class OwnerState { ACTIVE, RETIRED, CLOSE_UNCONFIRMED }
    private var ownerState = OwnerState.ACTIVE
    private var cleanupPending = false
    private val lines = mutableListOf<String>()
    private var vdDisplayId: Int? = null
    private var totalReads = 0
    private var candidateFirst = true

    override fun open(): JsonObject = runBlocking {
        operations.withLock {
            synchronized(lock) {
                if (ownerState != OwnerState.ACTIVE) return@withLock reply(false, "session_not_open", vdDisplayId)
                if (vdDisplayId != null) return@withLock reply(true, "already_open", vdDisplayId)
            }
            try {
                val opened = withTimeout(OPEN_TIMEOUT_MS) {
                    check(shell.connectAuto()) { "ADB connection unavailable" }
                    session.open()
                }
                synchronized(lock) { vdDisplayId = opened.displayId }
                buildJsonObject {
                    put("accepted", true)
                    put("reason", "opened")
                    put("vdDisplayId", opened.displayId)
                    put("helperPid", opened.helperPid)
                }
            } catch (error: Exception) {
                Log.w(TAG, "open failed: ${error.javaClass.name}: ${error.message}")
                reply(false, "open_failed:${error.javaClass.simpleName}")
            }
        }
    }

    override fun pairedReads(displayId: Int, reads: Int): JsonObject = runBlocking {
        operations.withLock {
            val (openedDisplayId, first) = synchronized(lock) {
                if (ownerState != OwnerState.ACTIVE || vdDisplayId == null) {
                    return@withLock reply(false, "session_not_open")
                }
                vdDisplayId to candidateFirst
            }
            try {
                val batch = List(reads) { index ->
                    ensureActive()
                    val read = driver.pairedRead(displayId, if (index % 2 == 0) first else !first)
                    VdTaskEquivalence.render(read)
                }
                val count = synchronized(lock) {
                    lines += batch
                    totalReads += batch.size
                    if (batch.size % 2 != 0) candidateFirst = !first
                    totalReads
                }
                buildJsonObject {
                    put("accepted", true)
                    put("reason", "ok")
                    put("displayId", displayId)
                    put("lines", JsonArray(batch.map(::JsonPrimitive)))
                    put("totalReads", count)
                }
            } catch (error: Exception) {
                Log.w(TAG, "paired reads failed: ${error.javaClass.name}: ${error.message}")
                reply(false, "read_failed:${error.javaClass.simpleName}", openedDisplayId)
            }
        }
    }

    override fun close(): JsonObject = runBlocking {
        operations.withLock {
            val failure = closeSessionLocked()
            if (failure != null) return@withLock reply(false, failure)
            synchronized(lock) {
                cleanupPending = false
                if (ownerState == OwnerState.CLOSE_UNCONFIRMED) ownerState = OwnerState.ACTIVE
            }
            reply(true, "closed")
        }
    }

    /** Refuse queued open/read requests during teardown and report whether cleanup was confirmed. */
    fun shutdown(): Boolean {
        synchronized(lock) { ownerState = OwnerState.RETIRED }
        return runBlocking {
            operations.withLock {
                val confirmed = closeSessionLocked() == null
                synchronized(lock) {
                    cleanupPending = !confirmed
                    if (!confirmed) ownerState = OwnerState.CLOSE_UNCONFIRMED
                }
                confirmed
            }
        }
    }

    /** Called with [operations] held. Returns a failure reason if cleanup was unconfirmed. */
    private suspend fun closeSessionLocked(): String? {
        synchronized(lock) { vdDisplayId = null }
        return try {
            session.close()
            null
        } catch (error: Exception) {
            Log.w(TAG, "close failed: ${error.javaClass.name}: ${error.message}")
            "close_failed:${error.javaClass.simpleName}"
        }
    }

    override fun status(): JsonObject {
        val snapshot = synchronized(lock) { Snapshot(vdDisplayId, totalReads, lines.toList(), cleanupPending) }
        return buildJsonObject {
            put("sessionOpen", snapshot.displayId != null)
            put("vdDisplayId", snapshot.displayId?.let(::JsonPrimitive) ?: JsonNull)
            put("totalReads", snapshot.totalReads)
            put("lines", JsonArray(snapshot.lines.map(::JsonPrimitive)))
            put("cleanupPending", snapshot.cleanupPending)
        }
    }

    private data class Snapshot(
        val displayId: Int?,
        val totalReads: Int,
        val lines: List<String>,
        val cleanupPending: Boolean,
    )

    private fun reply(accepted: Boolean, reason: String, displayId: Int? = null) = buildJsonObject {
        put("accepted", accepted)
        put("reason", reason)
        displayId?.let { put("vdDisplayId", it) }
    }

    private companion object {
        const val TAG = "VdTaskEquivalenceTool"
        const val OPEN_TIMEOUT_MS = 60_000L
    }
}
