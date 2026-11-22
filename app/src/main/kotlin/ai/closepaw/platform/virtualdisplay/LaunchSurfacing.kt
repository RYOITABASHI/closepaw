package ai.closepaw.platform.virtualdisplay

internal sealed interface LaunchSurfacingAssessment {
    data object Surfaced : LaunchSurfacingAssessment
    data class PresentNotFront(val taskId: Int) : LaunchSurfacingAssessment
    data object Absent : LaunchSurfacingAssessment
}

internal enum class LaunchSurfacingFailure {
    NEVER_APPEARED, NOT_BROUGHT_TO_FRONT, UNVERIFIABLE
}

internal object LaunchSurfacingLimits {
    const val TIMEOUT_MS = 3_000L
    const val POLL_MS = 150L
}

internal object LaunchSurfacing {
    fun deadlineMs(startMs: Long): Long = startMs + LaunchSurfacingLimits.TIMEOUT_MS

    fun mayStartOperation(nowMs: Long, deadlineMs: Long): Boolean = nowMs < deadlineMs

    fun observationCounts(completedMs: Long, deadlineMs: Long): Boolean =
        completedMs < deadlineMs

    fun nextPollDelayMs(nowMs: Long, deadlineMs: Long): Long =
        (deadlineMs - nowMs).coerceIn(0L, LaunchSurfacingLimits.POLL_MS)

    /**
     * Reads [tasks] in top-most-first order. Pinned (PiP) tasks always sit on top and never
     * count as the app in front, so they are ignored entirely, including target PiP tasks.
     * The first non-pinned task is the effective top. It counts as surfaced only if its package
     * equals [targetPackage]. A null package can belong to an empty or organizer task.
     * Otherwise, return the first non-pinned target task's ID, or Absent if there is none.
     */
    fun assess(tasks: List<DisplayTask>, targetPackage: String): LaunchSurfacingAssessment {
        val nonPinnedTasks = tasks.asSequence().filterNot { it.pinned }
        if (nonPinnedTasks.firstOrNull()?.packageName == targetPackage) {
            return LaunchSurfacingAssessment.Surfaced
        }

        val targetTask = nonPinnedTasks.firstOrNull { it.packageName == targetPackage }
            ?: return LaunchSurfacingAssessment.Absent
        return LaunchSurfacingAssessment.PresentNotFront(targetTask.taskId)
    }

    /**
     * A seen target takes precedence. Otherwise, a readable observation means the target never
     * appeared. With no readable observations, surfacing is unverifiable.
     */
    fun failureKind(
        sawTargetTask: Boolean,
        sawReadableObservation: Boolean,
    ): LaunchSurfacingFailure = when {
        sawTargetTask -> LaunchSurfacingFailure.NOT_BROUGHT_TO_FRONT
        sawReadableObservation -> LaunchSurfacingFailure.NEVER_APPEARED
        else -> LaunchSurfacingFailure.UNVERIFIABLE
    }
}
