package ai.closepaw.platform.virtualdisplay

internal enum class TaskWriteResult { APPLIED, NOT_APPLIED, FAILED, UNKNOWN }

internal fun decodeTaskWriteResult(value: Any?): TaskWriteResult = when (value) {
    true -> TaskWriteResult.APPLIED
    false -> TaskWriteResult.NOT_APPLIED
    else -> TaskWriteResult.UNKNOWN
}

/** A lost acknowledgement may follow a committed write. Leave recovery to reconciliation. */
internal fun TaskWriteResult.resolve(retryNegative: Boolean, fallback: () -> Boolean?): Boolean? =
        when (this) {
            TaskWriteResult.APPLIED -> true
            TaskWriteResult.NOT_APPLIED -> if (retryNegative) fallback() else false
            TaskWriteResult.FAILED -> fallback()
            TaskWriteResult.UNKNOWN -> null
        }

/** Serializes the sender's start decision with the caller's decision to allow fallback. */
internal class TaskWriteDispatch(private val deadlineNanos: Long) {
    private enum class State { QUEUED, STARTED, ABORTED }

    private var state = State.QUEUED

    @Synchronized
    fun tryStart(): Boolean {
        if (state != State.QUEUED || deadlineNanos - System.nanoTime() <= 0) return false
        state = State.STARTED
        return true
    }

    @Synchronized
    fun abort(): TaskWriteResult {
        if (state == State.STARTED) return TaskWriteResult.UNKNOWN
        state = State.ABORTED
        return TaskWriteResult.FAILED
    }
}
