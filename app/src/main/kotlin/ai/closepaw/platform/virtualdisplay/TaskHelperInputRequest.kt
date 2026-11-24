package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperInput
import ai.closepaw.taskhelper.TaskHelperWire
import android.os.Bundle

internal data class TaskHelperInputRequest(
    val kind: Int,
    val displayId: Int,
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val durationMs: Long = 0,
    val action: Int = 0,
    val downTime: Long = 0,
    val eventTime: Long = 0,
) {
    val valid: Boolean get() = TaskHelperInput.valid(
        kind, displayId, x, y, endX, endY, durationMs, action, downTime, eventTime,
    )

    val timeoutMs: Long get() = durationMs + 2_000L

    fun payload(id: Int, deadline: Long) = Bundle().apply {
        putInt(TaskHelperWire.REQUEST_ID, id)
        putInt(TaskHelperWire.DISPLAY_ID, displayId)
        putInt(TaskHelperWire.OPERATION, kind)
        putFloat("x", x)
        putFloat("y", y)
        putFloat("endX", endX)
        putFloat("endY", endY)
        putLong("duration", durationMs)
        putInt("action", action)
        putLong("downTime", downTime)
        putLong("eventTime", eventTime)
        putLong("deadline", deadline)
    }
}
