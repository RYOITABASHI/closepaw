package ai.closepaw.agent.cognition.policy

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.ActionResult
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

        /** Capture elements as parser nodes (the parser scopes them to the target app and sorts them). */
        fun nodes(snapshot: ScreenSnapshot): List<ScreenNode> =
            snapshot.elements.map {
                ScreenNode(
                    text = it.text,
                    description = it.description,
                    top = it.bounds.top,
                    left = it.bounds.left,
                    packageName = it.packageName,
                )
            }
    }

    suspend fun verify(spec: ListGoalSpec): ListVerification {
        val screens = ArrayList<ParsedListScreen>()
        val targetPackage = runCatching { platform.getCurrentPackageName() }.getOrNull()
        var parsed = ListScreenParser.parse(spec, nodes(platform.captureScreen()), targetPackage)
        screens.add(parsed)
        if (!parsed.onTargetScreen) {
            Log.i(TAG, "verify: not on target list \"${spec.listName}\" (app=$targetPackage)")
            return decide(spec, screens)
        }
        val known = parsed.rows.map { it.key }.toMutableSet()
        var swipes = 0
        val info = platform.getDisplayInfo()
        val x = info.widthPixels / 2
        val yLow = (info.heightPixels * 0.72).toInt()
        val yHigh = (info.heightPixels * 0.32).toInt()
        // Scrolling is best effort: a failed or ineffective swipe just ends the scan and we
        // decide from what was visible (never fail the run over it).
        while (!parsed.reachedRecommendations && swipes < maxSwipes) {
            val swipe = runCatching { platform.performAction(UIAction.Swipe(x, yLow, x, yHigh, 350)) }.getOrNull()
            if (swipe !is ActionResult.Success) break
            swipes++
            delay(settleMs)
            parsed = ListScreenParser.parse(spec, nodes(platform.captureScreen()), targetPackage)
            screens.add(parsed)
            val before = known.size
            known.addAll(parsed.rows.map { it.key })
            if (!parsed.onTargetScreen || known.size == before) break
        }
        // Put the page back where the agent left it so its own next scroll still has an effect.
        repeat(swipes) {
            runCatching { platform.performAction(UIAction.Swipe(x, yHigh, x, yLow, 300)) }
            delay(settleMs / 2)
        }
        val result = decide(spec, screens)
        Log.i(TAG, "verify: app=$targetPackage list=\"${spec.listName}\" rows=${known.size} swipes=$swipes -> ${result.rejection ?: "OK"}")
        return result
    }
}
