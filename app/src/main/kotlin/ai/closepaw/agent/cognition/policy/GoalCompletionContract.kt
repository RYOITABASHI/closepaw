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
        "- Do not start media playback. In music apps, NEVER tap a song row, song title, cover art or play button. Add a song only through the row's \"...\" (more) menu -> \"Add to playlist\" -> pick the playlist, or through the search result's \"+\" / \"Add\" button. Wait for the \"Added to <playlist>\" confirmation after each add; an add without that confirmation does not count.",
        "- To add a specific song: use the app's Search, type \"<artist> <title>\", and add it from the SEARCH RESULT row's \"...\" menu -> Add to playlist (or its \"+\" button). Never add from Recents, Recommended, \"Based on ...\", Home or autoplay sections, and never add a song by an artist the task did not ask for.",
        "- Verification is done by ClosePaw, not by you: when you believe the list is complete, open the playlist page so its rows are visible and call complete_task. ClosePaw counts the rows and tells you exactly what is missing or extra; follow that instruction instead of guessing.",
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
    fun completionRejection(
        goal: String,
        status: String,
        answer: String,
        /** Confirmations ClosePaw itself observed ([EvidenceLedger.count]); null = no evidence tracking. */
        observed: Int? = null,
    ): String? {
        if (status.trim().lowercase() == "failure") return null
        val required = requiredCount(goal) ?: return null
        val reported = reportedCount(answer)
        if (observed != null) {
            // Device-side evidence decides; the agent's own count is neither required nor trusted.
            if (observed >= required) return null
            return "NOT FINISHED: observed $observed of $required. ClosePaw counted only $observed " +
                "on-screen confirmation(s) such as \"Added to <playlist>\"; your own count is not " +
                "evidence. Keep adding one item at a time and wait for each confirmation, then call " +
                "complete_task again."
        }
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

    /** Gate for [TurnToolPolicy]: bound to one goal; [observed] supplies device-side evidence. */
    fun gateFor(goal: String, observed: (() -> Int?) = { null }): (status: String, answer: String) -> String? =
        { status, answer -> completionRejection(goal, status, answer, observed()) }

    /** The user's own task text when [wrap] was applied (so the contract never counts as a requirement). */
    internal fun taskBody(goal: String): String {
        val marker = "\nTASK:\n"
        val idx = goal.indexOf(marker)
        return if (goal.startsWith(CONTRACT_HEADER) && idx >= 0) goal.substring(idx + marker.length) else goal
    }
}
