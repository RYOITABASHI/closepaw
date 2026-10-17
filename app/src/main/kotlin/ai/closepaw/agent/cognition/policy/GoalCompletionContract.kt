package ai.closepaw.agent.cognition.policy

/**
 * Completion contract for unattended (Shelly-handed) goals.
 *
 * Two layers, both pure so they are unit-testable:
 *  1. [wrap] prepends an explicit contract to the task text the agent sees: re-read the task,
 *     verify every required item / count on screen before `complete_task`, never declare success
 *     after part of the items, and never start media playback while adding songs.
 *  2. [completionRejection] is the hard gate used by [TurnToolPolicy]: for count-based goals
 *     ("add 10 songs") a `complete_task(success)` whose answer does not state a verified count of
 *     at least the required number is rejected and the agent keeps working.
 */
object GoalCompletionContract {

    private val COUNT_UNITS =
        "songs?|tracks?|items?|entries|entry|photos?|pictures?|contacts?|messages?|emails?|files?|" +
            "products?|results?|links?|videos?|albums?|playlists?|events?|tasks?|notes?|rows?|times"

    /** "10 songs", "ten" is not parsed (digits only), "5 items" … */
    private val COUNT_RE = Regex("\\b(\\d{1,4})\\s+(?:$COUNT_UNITS)\\b", RegexOption.IGNORE_CASE)

    /** "10 of 10", "10/10", "7 out of 10" in an answer. */
    private val PROGRESS_RE = Regex("\\b(\\d{1,4})\\s*(?:/|of|out of)\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)

    private const val CONTRACT_HEADER = "COMPLETION CONTRACT (unattended run):"

    private val CONTRACT_LINES = listOf(
        "- Before you call complete_task, re-read the whole task below, list every required item and count, and verify each one on screen.",
        "- If anything is missing, keep working. Never declare success after only part of the items; partial work is a failure, not a success.",
        "- For count-based requests, state the verified count in your answer (for example \"added 10 of 10\") only after counting on screen.",
        "- Do not start media playback. Add songs through the track's \"...\" / \"+\" / \"Add to playlist\" controls, never by tapping the song title or a play button. If playback starts, pause it before continuing.",
        "- Nobody is watching; do not stop to ask for confirmation of steps the task already spells out.",
    )

    /** The task text handed to the agent for a Shelly-handed goal. */
    fun wrap(goal: String): String {
        val trimmed = goal.trim()
        if (trimmed.startsWith(CONTRACT_HEADER)) return trimmed
        return buildString {
            append(CONTRACT_HEADER).append('\n')
            CONTRACT_LINES.forEach { append(it).append('\n') }
            append('\n')
            append("TASK:\n").append(trimmed)
        }
    }

    /** Largest "N <unit>" in the goal, or null when the goal is not count-based. */
    fun requiredCount(goal: String): Int? =
        COUNT_RE.findAll(taskBody(goal)).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

    /**
     * The count the agent claims to have verified: the largest "N <unit>" or the first number of
     * an "N of M" / "N/M" progress phrase. Null when the answer states no count at all.
     */
    fun reportedCount(answer: String): Int? {
        val unitCounts = COUNT_RE.findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull() }
        val progressCounts = PROGRESS_RE.findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull() }
        return (unitCounts + progressCounts).maxOrNull()
    }

    /**
     * Null = completion accepted. Otherwise the message to feed back to the agent instead of
     * finishing. Only `success` completions of count-based goals are gated; failures pass through
     * (the agent is allowed to give up with an explanation).
     */
    fun completionRejection(goal: String, status: String, answer: String): String? {
        if (status.trim().lowercase() == "failure") return null
        val required = requiredCount(goal) ?: return null
        val reported = reportedCount(answer)
        return when {
            reported == null ->
                "NOT FINISHED: the task requires $required items. Count them on screen and state the " +
                    "verified count in your answer (e.g. \"$required of $required\"); if fewer than " +
                    "$required are there, keep adding."
            reported < required ->
                "NOT FINISHED: $reported of $required. Keep working until all $required are done, " +
                    "verify the count on screen, then call complete_task again."
            else -> null
        }
    }

    /** Gate for [TurnToolPolicy]: bound to one goal. */
    fun gateFor(goal: String): (status: String, answer: String) -> String? =
        { status, answer -> completionRejection(goal, status, answer) }

    /** The user's own task text when [wrap] was applied (so the contract never counts as a requirement). */
    private fun taskBody(goal: String): String {
        val marker = "\nTASK:\n"
        val idx = goal.indexOf(marker)
        return if (goal.startsWith(CONTRACT_HEADER) && idx >= 0) goal.substring(idx + marker.length) else goal
    }
}
