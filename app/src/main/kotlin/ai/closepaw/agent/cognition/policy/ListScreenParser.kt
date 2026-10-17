package ai.closepaw.agent.cognition.policy

/** One item row read from a list screen. */
data class ListRow(val title: String, val artist: String) {
    val key: String get() = ListGoalSpec.normalize(title) + "|" + ListGoalSpec.normalize(artist)
    override fun toString(): String = "$title — $artist"
}

/** Deterministic reading of one screen transcript of the target list. */
data class ParsedListScreen(
    /** The quoted list name is visible: we are looking at the target list. */
    val onTargetScreen: Boolean,
    /** Item rows above any recommendation section, in screen order. */
    val rows: List<ListRow>,
    /** A recommendations / suggestions section was reached (nothing below it counts). */
    val reachedRecommendations: Boolean,
)

/**
 * Reads item rows out of a screen transcript (element texts in screen order) of a playlist /
 * list page. Layout assumption (Spotify and most media apps): each row is a title line followed
 * by an artist line, optionally with a short meta line ("Video • ", "E • ") in between. Rows
 * under a "Recommended" / "Based on …" / "Suggested" header are never counted.
 */
object ListScreenParser {
    private val RECOMMENDATION_RE = Regex(
        "^(?:recommended(?: songs| for you)?|based on (?:the songs|this playlist|your)|suggested(?: songs)?|" +
            "you might also like|similar (?:songs|tracks)|more like this|おすすめ|このプレイリストの曲に基づく.*)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val META_LINE_RE = Regex("^(?:video|e|explicit|lyrics|live|new)\\s*[•·]\\s*$", RegexOption.IGNORE_CASE)
    private val COUNT_META_RE = Regex("^\\d+\\s+(?:songs?|tracks?|items?|episodes?)\\b.*|^\\d+\\s*(?:min|sec|hr|h|m|s)\\b.*", RegexOption.IGNORE_CASE)
    private val CHROME = setOf(
        "navigate up", "back", "home", "search", "your library", "premium", "create", "library",
        "add to this playlist", "add songs", "add to playlist", "edit", "edit playlist", "download", "downloaded",
        "more options", "more", "shuffle", "play", "pause", "sort", "sort by", "search in playlist", "filter",
        "share", "like", "add", "refresh", "see all", "playlist", "public playlist", "private playlist",
        "enhance", "preview", "invite collaborators", "collaborative playlist",
    )
    private val CHROME_PREFIX = listOf("more options for", "play ", "shuffle play", "added by", "made for", "by ")
    private val DURATION_RE = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
    private const val MAX_LINE = 80

    fun parse(spec: ListGoalSpec, lines: List<String>): ParsedListScreen {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val listName = spec.listName
        val onTarget = listName == null || cleaned.any { it.equals(listName, ignoreCase = true) }
        if (!onTarget) return ParsedListScreen(false, emptyList(), false)

        // Rows start after the header block: the last header/action line that precedes the items.
        var start = 0
        cleaned.forEachIndexed { i, line ->
            val l = line.lowercase()
            if (listName != null && line.equals(listName, ignoreCase = true)) start = i + 1
            if (COUNT_META_RE.matches(line) || l in setOf("shuffle", "play", "add to this playlist", "add songs", "download", "more options", "edit")) start = i + 1
        }

        val rows = ArrayList<ListRow>()
        val seen = HashSet<String>()
        var reachedRecommendations = false
        var pendingTitle: String? = null
        for (line in cleaned.drop(start)) {
            if (RECOMMENDATION_RE.containsMatchIn(line)) {
                reachedRecommendations = true
                break
            }
            if (isChrome(line) || META_LINE_RE.matches(line) || DURATION_RE.matches(line) || line.length > MAX_LINE) continue
            if (listName != null && line.equals(listName, ignoreCase = true)) continue
            if (pendingTitle == null) {
                pendingTitle = line
            } else {
                val artist = stripMeta(line)
                val row = ListRow(pendingTitle, artist)
                if (seen.add(row.key)) rows.add(row)
                pendingTitle = null
            }
        }
        return ParsedListScreen(true, rows, reachedRecommendations)
    }

    private fun stripMeta(line: String): String =
        line.replace(Regex("^(?:video|e|explicit|lyrics)\\s*[•·]\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun isChrome(line: String): Boolean {
        val l = line.lowercase().trimEnd('.', '…')
        if (l in CHROME) return true
        if (CHROME_PREFIX.any { l.startsWith(it) }) return true
        if (COUNT_META_RE.matches(line)) return true
        return false
    }
}
