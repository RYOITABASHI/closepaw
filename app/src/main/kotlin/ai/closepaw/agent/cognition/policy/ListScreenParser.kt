package ai.closepaw.agent.cognition.policy

/** One item row read from a list screen. */
data class ListRow(val title: String, val artist: String) {
    val key: String get() = ListGoalSpec.normalize(title) + "|" + ListGoalSpec.normalize(artist)
    override fun toString(): String = "$title — $artist"
}

/** Minimal view of an accessibility element for list parsing (what the verifier needs, testable without Android). */
data class ScreenNode(
    val text: String,
    val description: String,
    val top: Int,
    val left: Int,
    /** Owning app package; empty when unknown (older captures). */
    val packageName: String = "",
) {
    val label: String get() = text.ifBlank { description }
}

/** Deterministic reading of one screen transcript of the target list. */
data class ParsedListScreen(
    /** The target list page is visible (title + header metadata / row anchors). */
    val onTargetScreen: Boolean,
    /** Item rows above any recommendation section, in screen order. */
    val rows: List<ListRow>,
    /** A recommendations / suggestions section was reached (nothing below it counts). */
    val reachedRecommendations: Boolean,
)

/**
 * Reads item rows out of an accessibility capture of a playlist / list page.
 *
 * 1. Only nodes of the target app's window count (System UI, launcher, recents and status-bar
 *    texts that share the virtual display are dropped; when no package info exists, obvious
 *    system noise is filtered by pattern).
 * 2. Rows are anchored by the per-row context-menu control whose content description is
 *    "Open context menu for <title>" (Spotify); the artist is the next text line below the
 *    title, skipping meta lines such as "Video • ". A generic title/artist pairing fallback is
 *    used only for apps without such anchors and without "Add <title> to playlist" markers.
 * 3. Everything from a "Recommended Songs" / "Based on …" / "Suggested" header down is ignored,
 *    as is any row that carries an "Add <title> to playlist" control (a recommendation).
 * 4. The page counts as the target list only when the header title matches AND header
 *    metadata (owner / duration / download / play controls) or at least one row anchor exists.
 */
object ListScreenParser {
    private val RECOMMENDATION_RE = Regex(
        "^(?:recommended(?: songs| for you)?|based on (?:the songs|this playlist|your)|suggested(?: songs)?|" +
            "you might also like|similar (?:songs|tracks)|more like this|おすすめ|このプレイリストの曲に基づく.*)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ANCHOR_RE = Regex("^open context menu for (.+)$", RegexOption.IGNORE_CASE)
    private val ADD_MARKER_RE = Regex("^add (.+) to (?:this )?playlist$", RegexOption.IGNORE_CASE)
    private val META_LINE_RE = Regex("^(?:video|e|explicit|lyrics|live|new)\\s*[•·]?\\s*$", RegexOption.IGNORE_CASE)
    private val COUNT_META_RE = Regex("^\\d+\\s*(?:songs?|tracks?|items?|episodes?|min|sec|hr|h|m|s)\\b.*", RegexOption.IGNORE_CASE)
    private val HEADER_DESC_RE = Regex(
        "^(?:created by .+|download playlist.*|explore .+|add to this playlist|play playlist|edit playlist|mix playlist|switch to video|shuffle.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val SYSTEM_NOISE_RE = Regex(
        "^(?:battery \\d+ percent.*|\\d{1,2}:\\d{2}(?:\\s*[ap]m)?|\\d+ notifications?|recents|apps|screen capture|finder|no notifications|" +
            "(?:mon|tue|wed|thu|fri|sat|sun),? \\d{1,2}/\\d{1,2}|.*\\|\\s*battery.*|wi-?fi.*|bluetooth.*|do not disturb.*|airplane mode.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")
    private val CHROME = setOf(
        "navigate up", "back", "home", "search", "your library", "premium", "create", "library",
        "add to this playlist", "add songs", "add to playlist", "edit", "edit playlist", "download", "downloaded",
        "more options", "more", "shuffle", "play", "pause", "sort", "sort by", "search in playlist", "filter",
        "share", "like", "add", "refresh", "see all", "playlist", "public playlist", "private playlist",
        "enhance", "preview", "invite collaborators", "collaborative playlist", "artwork", "open context menu",
    )
    private val CHROME_PREFIX = listOf("more options for", "play ", "shuffle play", "added by", "made for", "by ", "tab ")
    private val CHROME_SUFFIX_RE = Regex(", tab \\d+ of \\d+$", RegexOption.IGNORE_CASE)
    private val DURATION_RE = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
    private const val MAX_LINE = 80
    private const val ROW_SPAN_PX = 260

    fun parse(spec: ListGoalSpec, nodes: List<ScreenNode>, targetPackage: String? = null): ParsedListScreen {
        val listName = spec.listName
        val scoped = scopeToApp(nodes, targetPackage)
            .map { it.copy(text = it.text.replace('\u00A0', ' '), description = it.description.replace('\u00A0', ' ')) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        if (scoped.isEmpty()) return ParsedListScreen(false, emptyList(), false)

        val titleNode = listName?.let { name -> scoped.firstOrNull { it.text.equals(name, ignoreCase = true) } }
        val anchors = scoped.filter { ANCHOR_RE.matches(it.description.trim()) }
        val hasHeaderMeta = scoped.any { n ->
            HEADER_DESC_RE.matches(n.description.trim()) || COUNT_META_RE.matches(n.text.trim()) ||
                (listName != null && n.description.contains(listName, ignoreCase = true) && !n.text.equals(listName, ignoreCase = true))
        }
        val onTarget = when {
            listName == null -> anchors.isNotEmpty() || hasHeaderMeta
            titleNode == null -> false
            else -> hasHeaderMeta || anchors.isNotEmpty()
        }
        if (!onTarget) return ParsedListScreen(false, emptyList(), false)

        val recommendationTop = scoped.filter { RECOMMENDATION_RE.containsMatchIn(it.text.trim()) }.minOfOrNull { it.top }
        val reachedRecommendations = recommendationTop != null
        val limitTop = recommendationTop ?: Int.MAX_VALUE
        val addMarkers = scoped.filter { ADD_MARKER_RE.matches(it.description.trim()) }

        val rows = if (anchors.isNotEmpty()) {
            anchoredRows(scoped, anchors, addMarkers, limitTop, listName)
        } else if (addMarkers.isNotEmpty()) {
            // Only recommendation-style rows are visible (e.g. an empty playlist page): nothing to count.
            emptyList()
        } else {
            fallbackRows(scoped, limitTop, listName, titleNode)
        }
        return ParsedListScreen(true, rows, reachedRecommendations)
    }

    // ── scoping ──

    private fun scopeToApp(nodes: List<ScreenNode>, targetPackage: String?): List<ScreenNode> {
        val withPkg = nodes.filter { it.packageName.isNotBlank() }
        val scoped = when {
            targetPackage != null && withPkg.any { it.packageName == targetPackage } ->
                nodes.filter { it.packageName == targetPackage }
            withPkg.isNotEmpty() -> nodes.filter { it.packageName !in SYSTEM_PACKAGES && !isLauncherPackage(it.packageName) }
            else -> nodes
        }
        return scoped.filter { n -> n.label.isNotBlank() && !SYSTEM_NOISE_RE.matches(n.label.trim()) }
    }

    private fun isLauncherPackage(pkg: String): Boolean =
        pkg.contains("launcher", ignoreCase = true) || pkg.endsWith(".home") || pkg.contains("trebuchet")

    // ── anchored rows (Spotify-style) ──

    private fun anchoredRows(
        scoped: List<ScreenNode>,
        anchors: List<ScreenNode>,
        addMarkers: List<ScreenNode>,
        limitTop: Int,
        listName: String?,
    ): List<ListRow> {
        val textNodes = scoped.filter { it.text.isNotBlank() }
        val rows = ArrayList<ListRow>()
        val seen = HashSet<String>()
        for (anchor in anchors) {
            if (anchor.top >= limitTop) continue
            val title = ANCHOR_RE.find(anchor.description.trim())?.groupValues?.get(1)?.trim() ?: continue
            // A recommendation row carries its own "Add <title> to playlist" control.
            if (addMarkers.any { m -> kotlin.math.abs(m.top - anchor.top) < ROW_SPAN_PX / 2 && ADD_MARKER_RE.find(m.description.trim())?.groupValues?.get(1)?.trim().equals(title, ignoreCase = true) }) continue
            val titleIdx = textNodes.indexOfFirst { n ->
                n.text.trim().equals(title, ignoreCase = true) && kotlin.math.abs(n.top - anchor.top) < ROW_SPAN_PX
            }
            val artist = if (titleIdx >= 0) {
                textNodes.drop(titleIdx + 1)
                    .takeWhile { it.top - textNodes[titleIdx].top < ROW_SPAN_PX }
                    .firstOrNull { n -> !META_LINE_RE.matches(n.text.trim()) && !isChrome(n.text, listName) && !ANCHOR_RE.matches(n.description.trim()) }
                    ?.let { stripMeta(it.text) }
            } else null
            val row = ListRow(title, artist ?: "")
            if (seen.add(row.key)) rows.add(row)
        }
        return rows
    }

    // ── generic fallback (title line followed by artist line) ──

    private fun fallbackRows(scoped: List<ScreenNode>, limitTop: Int, listName: String?, titleNode: ScreenNode?): List<ListRow> {
        val lines = scoped.filter { it.top < limitTop }.map { it.label.trim() }.filter { it.isNotEmpty() }
        var start = 0
        lines.forEachIndexed { i, line ->
            val l = line.lowercase()
            if (listName != null && line.equals(listName, ignoreCase = true)) start = i + 1
            if (COUNT_META_RE.matches(line) || l in setOf("shuffle", "play", "add to this playlist", "add songs", "download", "more options", "edit")) start = i + 1
        }
        val rows = ArrayList<ListRow>()
        val seen = HashSet<String>()
        var pendingTitle: String? = null
        for (line in lines.drop(start)) {
            if (isChrome(line, listName) || META_LINE_RE.matches(line) || DURATION_RE.matches(line) || line.length > MAX_LINE) continue
            if (pendingTitle == null) {
                pendingTitle = line
            } else {
                val row = ListRow(pendingTitle, stripMeta(line))
                if (seen.add(row.key)) rows.add(row)
                pendingTitle = null
            }
        }
        return rows
    }

    private fun stripMeta(line: String): String =
        line.replace(Regex("^(?:video|e|explicit|lyrics)\\s*[•·]\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun isChrome(line: String, listName: String?): Boolean {
        val l = line.trim().lowercase().trimEnd('.', '…')
        if (l.isEmpty()) return true
        if (listName != null && l == listName.lowercase()) return true
        if (l in CHROME) return true
        if (CHROME_PREFIX.any { l.startsWith(it) }) return true
        if (CHROME_SUFFIX_RE.containsMatchIn(l)) return true
        if (COUNT_META_RE.matches(line.trim())) return true
        if (HEADER_DESC_RE.matches(line.trim())) return true
        if (ADD_MARKER_RE.matches(line.trim()) || ANCHOR_RE.matches(line.trim())) return true
        return false
    }
}
