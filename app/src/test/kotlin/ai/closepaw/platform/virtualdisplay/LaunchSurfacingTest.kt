package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchSurfacingTest {
    private val targetPackage = "com.example.target"

    @Test
    fun surfacedWhenTargetIsTop() {
        val tasks = listOf(DisplayTask(1, targetPackage), DisplayTask(2, "com.example.launcher"))

        assertEquals(LaunchSurfacingAssessment.Surfaced, LaunchSurfacing.assess(tasks, targetPackage))
    }

    @Test
    fun surfacedWhenPinnedTaskOfAnotherPackageIsAboveTarget() {
        val tasks = listOf(DisplayTask(1, "com.example.video", pinned = true), DisplayTask(2, targetPackage))

        assertEquals(LaunchSurfacingAssessment.Surfaced, LaunchSurfacing.assess(tasks, targetPackage))
    }

    @Test
    fun presentNotFrontReturnsTheTargetTaskId() {
        val tasks = listOf(
            DisplayTask(1, "com.example.launcher"),
            DisplayTask(2, targetPackage),
            DisplayTask(3, targetPackage),
        )

        assertEquals(LaunchSurfacingAssessment.PresentNotFront(2), LaunchSurfacing.assess(tasks, targetPackage))
    }

    @Test
    fun presentNotFrontWhenTopPackageIsNull() {
        val tasks = listOf(DisplayTask(1, null), DisplayTask(2, targetPackage))

        assertEquals(LaunchSurfacingAssessment.PresentNotFront(2), LaunchSurfacing.assess(tasks, targetPackage))
    }

    @Test
    fun absentForEmptyTaskList() {
        assertEquals(LaunchSurfacingAssessment.Absent, LaunchSurfacing.assess(emptyList(), targetPackage))
    }

    @Test
    fun absentWhenOnlyOtherPackagesPresent() {
        val tasks = listOf(DisplayTask(1, "com.example.launcher"))

        assertEquals(LaunchSurfacingAssessment.Absent, LaunchSurfacing.assess(tasks, targetPackage))
    }

    @Test
    fun absentWhenTargetsOnlyTaskIsPinned() {
        val tasks = listOf(DisplayTask(1, targetPackage, pinned = true), DisplayTask(2, "com.example.launcher"))

        assertEquals(LaunchSurfacingAssessment.Absent, LaunchSurfacing.assess(tasks, targetPackage))
    }

    @Test
    fun failureKindNotBroughtToFrontWhenTargetWasSeen() {
        assertEquals(
            LaunchSurfacingFailure.NOT_BROUGHT_TO_FRONT,
            LaunchSurfacing.failureKind(sawTargetTask = true, sawReadableObservation = true),
        )
    }

    @Test
    fun failureKindNeverAppearedWhenReadableButTargetNeverSeen() {
        assertEquals(
            LaunchSurfacingFailure.NEVER_APPEARED,
            LaunchSurfacing.failureKind(sawTargetTask = false, sawReadableObservation = true),
        )
    }

    @Test
    fun failureKindUnverifiableWhenNoReadableObservation() {
        assertEquals(
            LaunchSurfacingFailure.UNVERIFIABLE,
            LaunchSurfacing.failureKind(sawTargetTask = false, sawReadableObservation = false),
        )
    }

    @Test
    fun failureKindPrefersNotBroughtToFrontWhenTargetWasSeen() {
        assertEquals(
            LaunchSurfacingFailure.NOT_BROUGHT_TO_FRONT,
            LaunchSurfacing.failureKind(sawTargetTask = true, sawReadableObservation = false),
        )
    }

    @Test
    fun surfacingLimitsAreBoundedAndOrdered() {
        assertEquals(3_000L, LaunchSurfacingLimits.TIMEOUT_MS)
        assertEquals(150L, LaunchSurfacingLimits.POLL_MS)
        assertTrue(LaunchSurfacingLimits.POLL_MS < LaunchSurfacingLimits.TIMEOUT_MS)
    }

    @Test
    fun observationCountsOnlyWhenCompletedBeforeDeadline() {
        val deadlineMs = 4_000L

        assertTrue(LaunchSurfacing.observationCounts(deadlineMs - 1, deadlineMs))
        assertFalse(LaunchSurfacing.observationCounts(deadlineMs, deadlineMs))
        assertFalse(LaunchSurfacing.observationCounts(deadlineMs + 1, deadlineMs))
    }

    @Test
    fun lateSurfacedAndPresentNotFrontLeaveNoEvidence() {
        val deadlineMs = 4_000L
        val lateSightings = listOf(
            deadlineMs to LaunchSurfacingAssessment.Surfaced,
            deadlineMs + 1 to LaunchSurfacingAssessment.PresentNotFront(2),
        )

        for (sighting in lateSightings) {
            assertEquals(LaunchSurfacingFailure.UNVERIFIABLE, failureFromObservations(deadlineMs, sighting))
        }
    }

    @Test
    fun latePresentNotFrontDoesNotOverrideInWindowAbsent() {
        val deadlineMs = 4_000L

        assertEquals(
            LaunchSurfacingFailure.NEVER_APPEARED,
            failureFromObservations(
                deadlineMs,
                deadlineMs - 1 to LaunchSurfacingAssessment.Absent,
                deadlineMs to LaunchSurfacingAssessment.PresentNotFront(2),
            ),
        )
    }

    @Test
    fun observationCanStartOnlyBeforeDeadline() {
        val deadlineMs = 4_000L

        assertTrue(LaunchSurfacing.mayStartOperation(deadlineMs - 1, deadlineMs))
        assertFalse(LaunchSurfacing.mayStartOperation(deadlineMs, deadlineMs))
        assertFalse(LaunchSurfacing.mayStartOperation(deadlineMs + 1, deadlineMs))
    }

    @Test
    fun deadlineAddsTimeoutToStart() {
        val startMs = 1_000L

        assertEquals(startMs + LaunchSurfacingLimits.TIMEOUT_MS, LaunchSurfacing.deadlineMs(startMs))
    }

    @Test
    fun nextPollDelayIsLimitedByRemainingWindow() {
        val deadlineMs = 4_000L

        assertEquals(LaunchSurfacingLimits.POLL_MS, LaunchSurfacing.nextPollDelayMs(2_500L, deadlineMs))
        assertEquals(25L, LaunchSurfacing.nextPollDelayMs(deadlineMs - 25, deadlineMs))
        assertTrue(LaunchSurfacing.nextPollDelayMs(deadlineMs, deadlineMs) <= 0)
        assertTrue(LaunchSurfacing.nextPollDelayMs(deadlineMs + 1, deadlineMs) <= 0)
    }

    private fun failureFromObservations(
        deadlineMs: Long,
        vararg observations: Pair<Long, LaunchSurfacingAssessment>,
    ): LaunchSurfacingFailure {
        val counted = observations.filter { (completedMs, _) ->
            LaunchSurfacing.observationCounts(completedMs, deadlineMs)
        }
        return LaunchSurfacing.failureKind(
            sawTargetTask = counted.any { (_, assessment) -> assessment != LaunchSurfacingAssessment.Absent },
            sawReadableObservation = counted.isNotEmpty(),
        )
    }
}
