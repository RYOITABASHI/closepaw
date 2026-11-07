package ai.closepaw.draft

/**
 * Allowlist for the article HTML. The text may derive from web research, and the paste lands in
 * the owner's signed-in editor origin, so anything that could run there is refused. The rule is
 * "reject", not "clean": a document the scanner cannot read fully is not pasted at all.
 */
object ArticleHtmlPolicy {
    private val BARE_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "ul", "ol", "li", "strong", "b", "em", "i",
        "blockquote", "pre", "code", "br", "hr", "figure", "figcaption",
    )
    private val ATTRIBUTES = mapOf(
        "a" to setOf("href"),
        "img" to setOf("src", "alt"),
    )
    private val URL_ATTRIBUTES = setOf("href", "src")
    private val TAG_RE = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\s+[a-zA-Z-]+="[^"<>]*")*)\s*/?>""")
    private val ATTRIBUTE_RE = Regex("""([a-zA-Z-]+)="([^"<>]*)"""")
    private val SAFE_URL_RE = Regex("""^https://[^\s\\]+$""")

    // Same set as JavaScript's \s, so both sides of the read-back normalize alike.
    private val SPACE_RE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    private const val MIN_SAMPLE_CHARS = 8
    private const val SAMPLE_CHARS = 40

    /** Null when [html] is acceptable, else a short reason that never quotes the input. */
    fun rejection(html: String): String? {
        var at = 0
        var hasText = false
        while (at < html.length) {
            val open = html.indexOf('<', at)
            val textEnd = if (open < 0) html.length else open
            if (html.substring(at, textEnd).isNotBlank()) hasText = true
            if (open < 0) break
            val tag = TAG_RE.matchAt(html, open) ?: return "html-unreadable-tag"
            if (!tagAllowed(tag)) return "html-tag-not-allowed"
            at = tag.range.last + 1
        }
        return if (hasText) null else "html-no-text"
    }

    private fun tagAllowed(tag: MatchResult): Boolean {
        val closing = tag.groupValues[1].isNotEmpty()
        val name = tag.groupValues[2].lowercase()
        val attributes = tag.groupValues[3]
        if (name !in BARE_TAGS && name !in ATTRIBUTES) return false
        if (closing) return attributes.isEmpty()
        val allowed = ATTRIBUTES[name].orEmpty()
        return ATTRIBUTE_RE.findAll(attributes).all { attribute ->
            val key = attribute.groupValues[1].lowercase()
            key in allowed && (key !in URL_ATTRIBUTES || SAFE_URL_RE.matches(attribute.groupValues[2]))
        }
    }

    fun normalize(text: String): String = text.replace(SPACE_RE, " ").trim()

    /**
     * Up to two literal fragments the saved body must contain: the start of the first text run
     * and the end of the last one. Runs with entities are skipped because the editor decodes them.
     */
    fun samples(html: String): List<String> {
        val runs = html.split(TAG_RE).map(::normalize).filter { it.isNotEmpty() && '&' !in it }
        val pool = runs.filter { it.length >= MIN_SAMPLE_CHARS }.ifEmpty { runs }
        if (pool.isEmpty()) return emptyList()
        return listOf(
            safeEnds(pool.first().take(SAMPLE_CHARS)),
            safeEnds(pool.last().takeLast(SAMPLE_CHARS)),
        ).filter { it.isNotEmpty() }.distinct()
    }

    /** A cut may split a surrogate pair; a lone half would never match the page text. */
    private fun safeEnds(text: String): String =
        text.dropWhile { it.isLowSurrogate() }.dropLastWhile { it.isHighSurrogate() }.trim()
}
