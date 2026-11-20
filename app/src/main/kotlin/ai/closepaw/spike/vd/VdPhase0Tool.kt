package ai.closepaw.spike.vd

import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.mcp.McpVdPhase0
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** The spike owns its run independently of the MCP request or server instance. */
internal class VdPhase0Tool(context: Context, private val shell: AdbShellChannel) : McpVdPhase0 {
    private val app = context.applicationContext
    private val store = VdStageStore(app)

    override fun status() = buildJsonObject {
        put("running", running.get())
        when (val load = store.load()) {
            VdStageLoad.Missing -> put("state", "missing")
            VdStageLoad.Invalid -> put("state", "invalid")
            is VdStageLoad.Present -> {
                val log = load.log
                put("state", if (log.stages.lastOrNull()?.stage == VdStage.CLEANUP) "complete" else "incomplete")
                put("runId", log.runId)
                put("passed", log.terminalSuccess)
                val persisted = log.json()
                put("stages", persisted.getValue("stages"))
                for (key in listOf("journal", "recovery", "endpointDeaths")) {
                    persisted[key]?.let { put(key, it) }
                }
                put("rerunAllowed", log.rerunAllowed)
                put("p1Passed", log.p1Passed)
                put("p1EndpointDeathsPassed", log.p1EndpointDeathsPassed)
            }
        }
    }

    override fun start(appDeathCheck: Boolean): JsonObject {
        if (!running.compareAndSet(false, true)) return reply(false, "already_running")
        return try {
            val previous = when (val load = store.load()) {
                VdStageLoad.Invalid -> return reject("invalid_previous_run")
                is VdStageLoad.Present -> load.log
                VdStageLoad.Missing -> null
            }
            if (previous != null && !previous.rerunAllowed) {
                worker { VdRecovery(shell).recover(previous, processToken, store::save) }
                return reply(false, "recovering_previous_run", previous.runId)
            }
            val log = VdStageLog(
                runId = UUID.randomUUID().toString(),
                journal = VdJournal(),
                endpointDeaths = if (appDeathCheck) null else previous?.endpointDeaths,
            )
            store.save(log)
            worker { VdPhase0Proofs(app, shell, log, store::save, processToken, appDeathCheck).run() }
            reply(true, "started", log.runId)
        } catch (_: Exception) {
            reject("stage_store_failed")
        }
    }

    private fun worker(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                Log.w("VdPhase0Tool", "Spike worker terminated: ${error.javaClass.simpleName}")
            } finally {
                running.set(false)
            }
        }
    }

    private fun reject(reason: String): JsonObject {
        running.set(false)
        return reply(false, reason)
    }

    private fun reply(accepted: Boolean, reason: String, runId: String? = null) = buildJsonObject {
        put("accepted", accepted)
        put("reason", reason)
        if (runId != null) put("runId", runId)
    }

    private companion object {
        val running = AtomicBoolean()
        val processToken = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
