package ai.closepaw.agent.cognition.policy

/**
 * What a count-based "build a list" goal asks for, parsed deterministically from the task text:
 * how many items, which named list/playlist they go into, and (optionally) which artists /
 * sources they must come from. Used by [ListGoalVerifier] to check the real screen instead of
 * trusting the agent's claim.
 */
data class ListGoalSpec(
    /** Required number of items ("add 10 songs"), null when the goal is not count-based. */
    val requiredCount: Int?,
    /** Quoted name of the target list ("Smells Like 1991"), null when none is quoted. */
    val listName: String?,
    /** Allowed artists / sources ("from Nirvana, Pearl Jam and Soundgarden"); empty = no constraint. */
    val artists: List<String>,
) {
    val isCounted: Boolean get() = requiredCount != null

    fun artistAllowed(artist: String): Boolean {
        if (artists.isEmpty()) return true
        val a = normalize(artist)
        return artists.any { allowed -> a.contains(normalize(allowed)) || normalize(allowed).contains(a) && a.length >= 3 }
    }

    companion object {
        private val QUOTED_RE = Regex("[\"“”「『]([^\"“”」』]{1,80})[\"“”」』]")
        private val FROM_RE = Regex(
            "\\b(?:from|by|of)\\s+((?:(?!\\b(?:then|and then|keep|until|after|before|so that)\\b)[^.;:!?\\n])+)",
            RegexOption.IGNORE_CASE,
        )
        private val SPLIT_RE = Regex("\\s*(?:,|;|/|&|\\band\\b|\\bor\\b)\\s*", RegexOption.IGNORE_CASE)
        private val NOT_AN_ARTIST = Regex("^(?:the\\s+)?(?:\\d{2,4}s?|(?:early|mid|late)\\s+\\d{2,4}s?|(?:that|this|the)\\s+(?:year|decade|era|period)|various artists?|any artists?|each artist|these artists)$", RegexOption.IGNORE_CASE)
        private val TRAILING_NOISE = Regex("\\s+(?:era|songs?|tracks?|albums?|only)$", RegexOption.IGNORE_CASE)

        fun parse(goal: String): ListGoalSpec {
            val body = GoalCompletionContract.taskBody(goal)
            val count = GoalCompletionContract.requiredCount(body)
            val name = QUOTED_RE.find(body)?.groupValues?.get(1)?.trim()
            val artists = parseArtists(body, name)
            return ListGoalSpec(count, name, artists)
        }

        /** Turn budget for an unattended run: generous per item, but bounded. */
        fun turnBudget(spec: ListGoalSpec): Int = 12 + 8 * (spec.requiredCount ?: 3)

        internal fun parseArtists(body: String, listName: String?): List<String> {
            val out = LinkedHashSet<String>()
            FROM_RE.findAll(body).forEach { m ->
                val phrase = m.groupValues[1].trim()
                // "from 1991" / "from the 90s": a period, not artists.
                phrase.split(SPLIT_RE).map { it.trim().trim('"', '“', '”').replace(TRAILING_NOISE, "").trim() }
                    .filter { it.isNotEmpty() && it.length <= 40 && it.first().isLetter() }
                    .filterNot { NOT_AN_ARTIST.matches(it) }
                    .filterNot { listName != null && it.equals(listName, ignoreCase = true) }
                    .filterNot { it.split(' ').size > 4 }
                    .forEach { out.add(it) }
            }
            // A single lowercase common word ("from search", "from your library") is not an artist.
            return out.filterNot { it.all { c -> c.isLowerCase() || c == ' ' } }
        }

        fun normalize(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
