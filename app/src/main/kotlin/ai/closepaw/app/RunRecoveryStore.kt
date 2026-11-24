package ai.closepaw.app

import ai.closepaw.mcp.McpRunRegistry
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** One process owns the store. Only records loaded from disk can be recovery candidates. */
internal class RunRecoveryStore(
    saved: Map<String, *>,
    private val write: (String, String) -> Boolean,
    private val remove: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = now,
    savedCancellations: Map<String, *> = emptyMap<String, String>(),
    private val writeCancellation: (String, String) -> Boolean = { _, _ -> false },
    private val removeCancellation: (String) -> Boolean = { true },
    private val memoryOnly: Boolean = false,
) {
    private data class Record(
        val startedAtMs: Long,
        val status: McpRunRegistry.Status,
        val summary: String,
        val correlation: ShellyBackgroundRun.ResultCorrelation?,
        val finishedAtMs: Long? = null,
        val callbackPending: Boolean = false,
        val sessionId: String? = null,
    ) {
        fun encode(): String = buildJsonObject {
            put("startedAtMs", startedAtMs)
            put("status", status.name)
            put("summary", summary)
            finishedAtMs?.let { put("finishedAtMs", it) }
            if (callbackPending) put("callbackPending", true)
            sessionId?.let { put("sessionId", it) }
            correlation?.let {
                put("reqId", it.reqId)
                put("nonce", it.nonce)
            }
        }.toString()
    }

    private val records = (saved + savedCancellations).mapNotNull { (handle, value) ->
        val record = try {
            val json = Json.parseToJsonElement(value as String) as JsonObject
            Record(
                json.getValue("startedAtMs").jsonPrimitive.long,
                McpRunRegistry.Status.valueOf(json.getValue("status").jsonPrimitive.content),
                json.getValue("summary").jsonPrimitive.content,
                ShellyBackgroundRun.parseResultCorrelation(
                    json["reqId"]?.jsonPrimitive?.contentOrNull,
                    json["nonce"]?.jsonPrimitive?.contentOrNull,
                ),
                json["finishedAtMs"]?.jsonPrimitive?.long,
                json["callbackPending"]?.jsonPrimitive?.booleanOrNull == true,
                json["sessionId"]?.jsonPrimitive?.contentOrNull,
            ).also {
                require(!it.callbackPending || (
                    it.status.terminal && it.finishedAtMs != null && it.correlation != null
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable run recovery record")
            null
        }
        record?.let { handle to it }
    }.toMap().toMutableMap()
    private val openedAt = elapsed()
    private val orphaned = records.filterValues { !it.status.terminal }.keys.toMutableSet()
    // Admission records STARTING before a worker or tracker can be registered.
    private val live = mutableSetOf<String>()
    private val dirty = savedCancellations.keys.filter {
        records[it]?.status == McpRunRegistry.Status.CANCELLED
    }.associateWith { CompletableDeferred<Unit>() }.toMutableMap()
    private val replay = records.filterValues { it.callbackPending }.keys.toMutableSet()
    private val attempted = mutableSetOf<String>()
    // A failed marker write retries the commit without sending again in this process.
    private val dispatched = mutableSetOf<String>()
    private val durableCancellations = mutableSetOf<String>()
    private val retry = Channel<Unit>(Channel.CONFLATED)

    internal data class WriteStatus(val failing: Boolean, val lastFailureAtMs: Long?)

    @Volatile
    var writeStatus = WriteStatus(memoryOnly, if (memoryOnly) now() else null)
        private set

    @Synchronized
    fun begin(handle: String, correlation: ShellyBackgroundRun.ResultCorrelation?): ShellyPendingResult {
        check(handle !in records)
        val record = Record(now(), McpRunRegistry.Status.STARTING, "", correlation)
        check(save(handle, record)) { "Could not persist active run" }
        live.add(handle)
        return pending(handle, correlation)
    }

    @Synchronized
    fun markRunning(handle: String) {
        val record = records[handle] ?: return
        if (record.status == McpRunRegistry.Status.STARTING) {
            save(handle, record.copy(status = McpRunRegistry.Status.RUNNING))
        }
    }

    @Synchronized
    fun snapshot(handle: String): McpRunRegistry.Snapshot? =
        records[handle]?.takeIf { it.status.terminal }?.let {
            McpRunRegistry.Snapshot(handle, it.status, it.summary)
        }

    @Synchronized
    fun recover(dispatch: (ShellyResultCallback.Payload) -> Boolean) {
        for (handle in orphaned.toList()) {
            val record = records.getValue(handle)
            // Time since this store opened is a lower bound on prior-process record age.
            val age = maxOf(now() - record.startedAtMs, elapsed() - openedAt)
            if (handle in live || age < GRACE_MS) continue
            val terminal = record.copy(
                status = McpRunRegistry.Status.FAILED,
                summary = "process_lost",
                finishedAtMs = now(),
                callbackPending = record.correlation != null,
            )
            if (save(handle, terminal)) {
                orphaned.remove(handle)
                if (terminal.callbackPending) replay.add(handle)
            }
        }

        for (handle in dispatched.toList()) clearPending(handle)
        for (handle in replay.toList()) {
            replay.remove(handle)
            val record = records.getValue(handle)
            val kind = when (record.status) {
                McpRunRegistry.Status.COMPLETED -> ShellyRunState.Finished.Kind.DONE
                McpRunRegistry.Status.CANCELLED -> ShellyRunState.Finished.Kind.STOPPED
                McpRunRegistry.Status.FAILED -> ShellyRunState.Finished.Kind.FAILED
                else -> error("Cannot replay a nonterminal run")
            }
            val payload = checkNotNull(ShellyResultCallback.brainPayload(
                record.correlation, kind, record.summary, checkNotNull(record.finishedAtMs), record.sessionId,
            ))
            dispatchPending(handle) { dispatch(payload) }
        }
        prune()
    }

    @Synchronized
    private fun dispatchPending(handle: String, dispatch: () -> Boolean) {
        if (records[handle]?.callbackPending != true || !attempted.add(handle)) return
        if (dispatch()) {
            dispatched.add(handle)
            if (clearPending(handle)) prune() else retry.trySend(Unit)
        }
    }

    private fun clearPending(handle: String): Boolean {
        // A cancellation fallback may still override the primary record on restart.
        if (handle in dirty || !save(handle, records.getValue(handle).copy(callbackPending = false))) return false
        dispatched.remove(handle)
        attempted.remove(handle)
        return true
    }

    @Synchronized
    fun hasPendingWork(): Boolean =
        orphaned.isNotEmpty() || dirty.isNotEmpty() || replay.isNotEmpty() || dispatched.isNotEmpty()

    @Synchronized
    fun flushTerminals(): List<CompletableDeferred<Unit>> {
        val persisted = dirty.keys.toList().mapNotNull { handle ->
            val record = records.getValue(handle)
            if (!save(handle, record)) return@mapNotNull null
            if (record.status == McpRunRegistry.Status.CANCELLED &&
                !tryWrite { removeCancellation(handle) }
            ) return@mapNotNull null
            durableCancellations.remove(handle)
            dirty.remove(handle)
        }
        prune()
        return persisted
    }

    private fun pending(handle: String, correlation: ShellyBackgroundRun.ResultCorrelation?) =
        ShellyPendingResult(
            correlation,
            persistTerminal = { kind, summary, finishedAtMs, sessionId ->
                complete(handle, kind, summary, finishedAtMs, sessionId)
            },
            durability = { synchronized(this) {
                dirty[handle]?.takeUnless { handle in durableCancellations }
            } },
            dispatchTerminal = { send -> dispatchPending(handle, send) },
        )

    @Synchronized
    private fun complete(
        handle: String,
        kind: ShellyRunState.Finished.Kind,
        summary: String?,
        finishedAtMs: Long,
        sessionId: String?,
    ): Boolean {
        val record = records.getValue(handle)
        if (record.status.terminal) return false
        val status = when (kind) {
            ShellyRunState.Finished.Kind.DONE -> McpRunRegistry.Status.COMPLETED
            ShellyRunState.Finished.Kind.FAILED -> McpRunRegistry.Status.FAILED
            ShellyRunState.Finished.Kind.STOPPED -> McpRunRegistry.Status.CANCELLED
        }
        val terminalRecord = record.copy(
            status = status,
            summary = ShellyResultCallback.truncateSummary(summary),
            finishedAtMs = finishedAtMs,
            callbackPending = record.correlation != null,
            sessionId = sessionId,
        )
        if (!save(handle, terminalRecord)) {
            if (status == McpRunRegistry.Status.CANCELLED) {
                // Preserve the stop across restart even when the primary terminal write fails.
                if (tryWrite { writeCancellation(handle, terminalRecord.encode()) }) {
                    durableCancellations.add(handle)
                } else {
                    Log.e(TAG, "Run recovery cancellation fallback write failed")
                }
            }
            records[handle] = terminalRecord
            dirty[handle] = CompletableDeferred()
            retry.trySend(Unit)
        }
        orphaned.remove(handle)
        live.remove(handle)
        prune()
        return true
    }

    private fun prune() {
        val terminal = records.entries.filter { (handle, record) ->
            record.status.terminal && !record.callbackPending && handle !in dirty
        }
        for (victim in terminal.sortedByDescending { it.value.finishedAtMs ?: it.value.startedAtMs }.drop(MAX_TERMINAL)) {
            if (tryWrite {
                remove(victim.key)
                true
            }) records.remove(victim.key)
        }
    }

    private fun save(handle: String, record: Record): Boolean {
        if (!tryWrite { write(handle, record.encode()) }) {
            writeStatus = WriteStatus(true, now())
            Log.e(TAG, "Run recovery write failed for state ${record.status}")
            return false
        }
        if (!memoryOnly && writeStatus.failing) writeStatus = writeStatus.copy(failing = false)
        records[handle] = record
        return true
    }

    private fun tryWrite(writeRecord: () -> Boolean): Boolean = try {
        writeRecord()
    } catch (e: Exception) {
        Log.e(TAG, "Run recovery storage operation failed: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "ClosePawRunRecovery"
        internal const val GRACE_MS = 5_000L
        private const val MAX_TERMINAL = 128
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var instance: RunRecoveryStore? = null

        internal fun open(
            openRecords: () -> SharedPreferences,
            openCancellations: () -> SharedPreferences,
            elapsed: () -> Long = System::currentTimeMillis,
        ): RunRecoveryStore = try {
            val prefs = openRecords()
            val saved = prefs.all
            val cancellations = openCancellations()
            RunRecoveryStore(
                saved,
                write = { handle, value -> prefs.edit().putString(handle, value).commit() },
                remove = { handle -> prefs.edit().remove(handle).apply() },
                elapsed = elapsed,
                savedCancellations = cancellations.all,
                writeCancellation = { handle, value -> cancellations.edit().putString(handle, value).commit() },
                removeCancellation = { handle -> cancellations.edit().remove(handle).commit() },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Run recovery store unreadable, using memory for this startup: ${e.javaClass.simpleName}")
            RunRecoveryStore(emptyMap<String, String>(), { _, _ -> true }, {}, elapsed = elapsed, memoryOnly = true)
        }

        @Synchronized
        fun get(context: Context): RunRecoveryStore {
            instance?.let { return it }
            val app = context.applicationContext
            val store = open(
                { app.getSharedPreferences("mcp_run_recovery", Context.MODE_PRIVATE) },
                { app.getSharedPreferences("mcp_run_recovery_cancellations", Context.MODE_PRIVATE) },
                SystemClock::elapsedRealtime,
            )
            instance = store
            scope.launch {
                do {
                    while (store.hasPendingWork()) {
                        store.recover { ShellyResultCallback.sendDurable(app, it, logRequestId = false) }
                        store.flushTerminals().forEach { it.complete(Unit) }
                        if (store.hasPendingWork()) delay(GRACE_MS)
                    }
                } while (store.retry.receiveCatching().isSuccess)
            }
            return store
        }
    }
}
