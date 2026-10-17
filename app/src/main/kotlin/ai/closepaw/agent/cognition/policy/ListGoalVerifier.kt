package ai.closepaw.agent.cognition.policy

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.UIAction
import android.util.Log
import kotlinx.coroutines.delay

/** Result of ClosePaw's own look at the target list. */
sealed interface ListVerification {
    /** Concrete instruction for the agent when the completion is refused; null when accepted. */
    val rejection: String?

    data class NotOnTargetScreen(val listName: String?) : ListVerification {
        override val rejection: String =
            "NOT VERIFIED: open the playlist/list " + (listName?.let { "\"$it\"" } ?: "you created") +
                " so its items are visible (Your Library -> the playlist), do nothing else there, then call " +
                "complete_task again. ClosePaw will count the rows itself."
    }

    data class Verified(
        val rows: List<ListRow>,
        val required: Int,
        val extraneous: List<ListRow>,
        val missingArtists: List<String>,
    ) : ListVerification {
        val count: Int get() = rows.size
        override val rejection: String?
            get() = when {
                extraneous.isNotEmpty() ->
                    "NOT FINISHED: the list contains item(s) that do not match the request. Remove these " +
                        "extra items (row's \"...\" menu -> Remove from this playlist): " +
                        extraneous.joinToString("; ") { "\"$it\"" } + ". Then call complete_task again."
                count < required ->
                    "NOT FINISHED: observed $count of $required in the list (${rows.joinToString("; ") { "\"$it\"" }.ifEmpty { "no rows" }}). " +
                        "Add ${required - count} more: for each, use Search with \"<artist> <title>\", then the result's " +
                        "\"...\" -> Add to playlist (or its \"+\"), never a Recents / Recommended row. Then open the " +
                        "playlist again and call complete_task."
                missingArtists.isNotEmpty() ->
                    "NOT FINISHED: the request asks for songs from ${missingArtists.joinToString(", ")} but none of " +
                        "theirs is in the list. Add one via Search (\"<artist> <title>\" -> \"...\" -> Add to playlist), " +
                        "remove an extra one if the total would exceed $required, then call complete_task again."
                else -> null
            }
    }
}

/**
 * Evidence-based verification of count-based list goals: ClosePaw reads the target list screen
 * itself (scrolling to the end, ignoring recommendation sections) and decides, without the LLM,
 * whether the goal is really done. Pure decision in [decide]; [verify] drives the screen.
 */
class ListGoalVerifier(
    private val platform: AndroidPlatform,
    private val settleMs: Long = 700L,
    private val maxSwipes: Int = 8,
) {
    companion object {
        private const val TAG = "ListGoalVerifier"

        fun decide(spec: ListGoalSpec, screens: List<ParsedListScreen>): ListVerification {
            val required = spec.requiredCount ?: 0
            val first = screens.firstOrNull() ?: return ListVerification.NotOnTargetScreen(spec.listName)
            if (!first.onTargetScreen) return ListVerification.NotOnTargetScreen(spec.listName)
            val rows = LinkedHashMap<String, ListRow>()
            screens.filter { it.onTargetScreen }.flatMap { it.rows }.forEach { rows.putIfAbsent(it.key, it) }
            val all = rows.values.toList()
            val extraneous = if (spec.artists.isEmpty()) emptyList() else all.filterNot { spec.artistAllowed(it.artist) }
            val present = spec.artists.filter { a -> all.any { r -> ListGoalSpec.normalize(r.artist).contains(ListGoalSpec.normalize(a)) } }
            val missing = if (spec.artists.size in 2..required) spec.artists - present.toSet() else emptyList()
            return ListVerification.Verified(all, required, extraneous, missing)
        }

        /** Screen transcript lines in reading order (top to bottom, left to right). */
        fun lines(snapshot: ScreenSnapshot): List<String> =
            snapshot.elements
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                .map { it.text.ifBlank { it.description } }
                .filter { it.isNotBlank() }
    }

    suspend fun verify(spec: ListGoalSpec): ListVerification {
        val screens = ArrayList<ParsedListScreen>()
        var parsed = ListScreenParser.parse(spec, lines(platform.captureScreen()))
        screens.add(parsed)
        if (!parsed.onTargetScreen) {
            Log.i(TAG, "verify: not on target list \"${spec.listName}\"")
            return decide(spec, screens)
        }
        var known = parsed.rows.map { it.key }.toMutableSet()
        var swipes = 0
        val info = platform.getDisplayInfo()
        while (!parsed.reachedRecommendations && swipes < maxSwipes) {
            val x = info.widthPixels / 2
            platform.performAction(
                UIAction.Swipe(x, (info.heightPixels * 0.72).toInt(), x, (info.heightPixels * 0.32).toInt(), 350)
            )
            delay(settleMs)
            parsed = ListScreenParser.parse(spec, lines(platform.captureScreen()))
            screens.add(parsed)
            swipes++
            val before = known.size
            known.addAll(parsed.rows.map { it.key })
            if (known.size == before) break
        }
        val result = decide(spec, screens)
        Log.i(TAG, "verify: list=\"${spec.listName}\" rows=${known.size} swipes=$swipes -> ${result.rejection ?: "OK"}")
        return result
    }
}
