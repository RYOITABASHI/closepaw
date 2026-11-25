package ai.closepaw.session

import android.util.Log
import ai.closepaw.history.HistoryManager
import ai.closepaw.history.SessionRecordingService
import ai.closepaw.history.model.CheckpointState
import ai.closepaw.history.model.ConversationConfigSnapshot
import ai.closepaw.history.model.HistoryItemConverter
import ai.closepaw.history.model.SessionRuntimeSnapshot
import ai.closepaw.history.model.TodoSnapshot
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionState

/**
 * Coordinates building and persisting LLM context snapshots.
 *
 * Owns the mapping from runtime state → [SessionRuntimeSnapshot].
 * Delegates actual I/O to [SessionRecordingService].
 */
internal class SessionCheckpointCoordinator(
    private val sessionId: String,
    private val config: SessionConfig,
    private val historyManager: HistoryManager,
    private val sessionState: AgentSessionState,
    private val recordingService: SessionRecordingService,
    private val externalDriver: Boolean = false,
) {
    companion object {
        private const val TAG = "CheckpointCoordinator"
    }

    fun scheduleCheckpoint(sessionState: SessionState) {
        val checkpointState = when (sessionState) {
            SessionState.Idle -> CheckpointState.IDLE_READY
            else -> CheckpointState.RUNNING_DIRTY
        }
        recordingService.scheduleCheckpoint { buildSnapshot(checkpointState) }
    }

    suspend fun flushIdleReady(): Boolean {
        val snapshot = buildSnapshot(CheckpointState.IDLE_READY)
        val success = recordingService.forceCheckpoint(snapshot)
        if (success) {
            Log.d(TAG, "Flushed IDLE_READY checkpoint for $sessionId")
        } else {
            Log.e(TAG, "Failed to flush IDLE_READY checkpoint for $sessionId")
        }
        return success
    }

    suspend fun flushClosed(): Boolean {
        val snapshot = buildSnapshot(CheckpointState.CLOSED)
        val success = recordingService.forceCheckpoint(snapshot)
        if (success) {
            Log.d(TAG, "Flushed CLOSED checkpoint for $sessionId")
        } else {
            Log.e(TAG, "Failed to flush CLOSED checkpoint for $sessionId")
        }
        return success
    }

    private fun buildSnapshot(state: CheckpointState): SessionRuntimeSnapshot {
        val items = historyManager.getAll()
        val todos = sessionState.todos.get()
        val scratchpadJson = sessionState.scratchpad.toJsonObject().toString()

        return SessionRuntimeSnapshot(
            schemaVersion = 2,
            sessionId = sessionId,
            config = config.toConfigSnapshot(),
            historyItems = HistoryItemConverter.toRecords(items),
            todos = todos.map { TodoSnapshot(description = it.description, status = it.status.name) },
            scratchpadJson = scratchpadJson,
            checkpointState = state,
            lastCheckpointAt = System.currentTimeMillis(),
            lastTaskOutcome = recordingService.getLastTaskOutcome()?.name,
            externalDriver = externalDriver,
        )
    }
}

internal fun SessionConfig.toConfigSnapshot() = ConversationConfigSnapshot(
    mainModel = "external",
    perceptionMode = perceptionConfig.toModeString(),
    platformMode = platformMode.name,
    actionDelayMs = actionDelayMs,
    approvalMode = approvalMode.name,
    debugMode = debugMode,
    traceEnabled = traceEnabled,
    traceRunId = traceRunId,
    excludedTools = excludedTools.toList()
)

private fun PerceptionConfig.toModeString(): String = when (this) {
    is PerceptionConfig.AccessibilityOnly -> "accessibility_only"
    is PerceptionConfig.ScreenshotOnly -> "screenshot_only"
    is PerceptionConfig.Hybrid -> "hybrid"
}
