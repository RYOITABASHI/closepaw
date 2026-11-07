package ai.closepaw.draft

/**
 * Allowlist for the article HTML. The text may derive from web research, and the paste lands in
 * the owner's signed-in editor origin, so the rule is "reject", not "clean": a document this
 * scanner cannot read fully is not pasted at all.
 *
 * The scanner is hand-written on purpose. It runs in linear time without recursion, so hostile
 * input cannot overflow the stack, and it uses the HTML tokenizer's own whitespace
 * ([HTML_SPACE]). A regex `\s` also matches VT, and on Android U+3000 and U+00A0, none of which
 * end a tag name for a browser, so the two would read one tag differently.
 *
 * What each URL can do:
 * - An `img` fires a GET with cookies as soon as the editor parses it, and shows the owner's IP
 *   to that host. So on note `img` is refused (images use the file's `images` list), and on
 *   Substack only exact hosts named by the caller pass, never a signed-in site.
 * - An `a` does not load on paste. It matters when someone follows it, and some editors unfurl a
 *   bare link from their servers. So links to the signed-in sites must be plain article links,
 *   which excludes state-changing paths and tokens in a query. Elsewhere the URL must be an
 *   ordinary https address: no user info, port or IP address, which is how a link is dressed up
 *   as another site or aimed at a device on the local network.
 */
object ArticleHtmlPolicy {
    private const val HTML_SPACE = " \t\n\u000C\r"
    private const val MAX_TAG_CHARS = 4096
    private const val MAX_ATTRIBUTES = 4
    private const val MAX_HOST_CHARS = 253

    private val BARE_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "ul", "ol", "li", "strong", "b", "em", "i",
        "blockquote", "pre", "code", "br", "hr", "figure", "figcaption",
    )
    private val ATTRIBUTES = mapOf(
        "a" to setOf("href"),
        "img" to setOf("src", "alt"),
    )
    private val SIGNED_IN_ROOTS = listOf("substack.com", "note.com")
    private val SUBSTACK_ARTICLE_PATH = Regex("^/p/[a-z0-9-]+/?$")
    private val NOTE_ARTICLE_PATH = Regex("^/[A-Za-z0-9_]+/n/n[a-z0-9]+/?$")

    // Same set as JavaScript's \s, so both sides of the read-back normalize alike.
    private val SPACE_RE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    private const val MIN_SAMPLE_CHARS = 8
    private const val SAMPLE_CHARS = 40

    private class Tag(
        val name: String,
        val closing: Boolean,
        val attributes: Map<String, String>,
        val end: Int,
    )

    /** Null when [html] is acceptable, else a short reason that never quotes the input. */
    fun rejection(html: String, platform: DraftPlatform, imageHosts: Set<String> = emptySet()): String? {
        var at = 0
        var hasText = false
        while (at < html.length) {
            val open = html.indexOf('<', at)
            val textEnd = if (open < 0) html.length else open
            if (html.substring(at, textEnd).isNotBlank()) hasText = true
            if (open < 0) break
            val tag = scanTag(html, open) ?: return "html-unreadable-tag"
            tagRejection(tag, platform, imageHosts)?.let { return it }
            at = tag.end
        }
        return if (hasText) null else "html-no-text"
    }

    /** A hostname for the caller's image list: exact, ordinary, and not a signed-in site. */
    fun isAllowableImageHost(host: String): Boolean = isOrdinaryHost(host) && !isSignedInHost(host)

    private fun tagRejection(tag: Tag, platform: DraftPlatform, imageHosts: Set<String>): String? {
        if (tag.name !in BARE_TAGS && tag.name !in ATTRIBUTES) return "html-tag-not-allowed"
        if (tag.closing) return if (tag.attributes.isEmpty()) null else "html-tag-not-allowed"
        val allowed = ATTRIBUTES[tag.name].orEmpty()
        if (!allowed.containsAll(tag.attributes.keys)) return "html-tag-not-allowed"
        return when (tag.name) {
            "img" -> imageRejection(tag, platform, imageHosts)
            "a" -> linkRejection(tag)
            else -> null
        }
    }

    private fun imageRejection(tag: Tag, platform: DraftPlatform, imageHosts: Set<String>): String? {
        if (platform != DraftPlatform.SUBSTACK) return "html-image-not-allowed"
        val url = tag.attributes["src"]?.let(::parseUrl) ?: return "html-image-not-allowed"
        val allowed = url.host in imageHosts && isAllowableImageHost(url.host)
        return if (allowed) null else "html-image-not-allowed"
    }

    private fun linkRejection(tag: Tag): String? {
        val url = tag.attributes["href"]?.let(::parseUrl) ?: return "html-link-not-allowed"
        if (!isSignedInHost(url.host)) return null
        return if (isPlainArticleLink(url)) null else "html-link-not-allowed"
    }

    private fun isSignedInHost(host: String): Boolean =
        SIGNED_IN_ROOTS.any { host == it || host.endsWith(".$it") }

    private fun isPlainArticleLink(url: Url): Boolean {
        if (url.hasQueryOrFragment) return false
        return when {
            url.host == "note.com" -> NOTE_ARTICLE_PATH.matches(url.path)
            url.host.endsWith(".substack.com") -> SUBSTACK_ARTICLE_PATH.matches(url.path)
            else -> false
        }
    }

    private class Url(val host: String, val path: String, val hasQueryOrFragment: Boolean)

    /**
     * Strict reading of an attribute value. `&amp;` is the only entity allowed, because any other
     * could hide a `?`, `/` or `#` from this check and reappear when the browser decodes it.
     */
    private fun parseUrl(raw: String): Url? {
        if (!raw.startsWith("https://")) return null
        if (raw.any { it == '\\' || it == '\'' || it == '"' || it == '`' || !isUrlChar(it) }) return null
        val decoded = raw.replace("&amp;", "\u0000")
        if ('&' in decoded) return null
        val rest = decoded.removePrefix("https://")
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd)
        if (!isOrdinaryHost(host)) return null
        val tail = rest.substring(hostEnd)
        val pathEnd = tail.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) tail.length else it }
        return Url(host, tail.substring(0, pathEnd), pathEnd < tail.length)
    }

    private fun isUrlChar(c: Char): Boolean = c in '!'..'~'

    /** Lowercase dotted name. No user info, port, IP address or trailing dot can pass. */
    private fun isOrdinaryHost(host: String): Boolean {
        if (host.isEmpty() || host.length > MAX_HOST_CHARS) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (!labels.all(::isHostLabel)) return false
        return labels.last().any { it in 'a'..'z' }
    }

    private fun isHostLabel(label: String): Boolean =
        label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    /** Reads one tag starting at the `<` at [start]. Null for anything but the strict form. */
    private fun scanTag(html: String, start: Int): Tag? {
        val limit = minOf(html.length, start + MAX_TAG_CHARS)
        var i = start + 1
        val closing = i < limit && html[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < limit && isNameChar(html[i])) i++
        if (i == nameStart || !html[nameStart].isAsciiLetter()) return null
        val name = html.substring(nameStart, i).lowercase()

        val attributes = LinkedHashMap<String, String>()
        while (true) {
            val beforeSpace = i
            while (i < limit && html[i] in HTML_SPACE) i++
            if (i >= limit) return null
            when (html[i]) {
                '>' -> return Tag(name, closing, attributes, i + 1)
                '/' -> return if (i + 1 < limit && html[i + 1] == '>') Tag(name, closing, attributes, i + 2) else null
            }
            if (i == beforeSpace || attributes.size == MAX_ATTRIBUTES) return null

            val keyStart = i
            while (i < limit && (html[i].isAsciiLetter() || html[i] == '-')) i++
            val key = html.substring(keyStart, i).lowercase()
            if (key.isEmpty() || key in attributes) return null
            if (i + 1 >= limit || html[i] != '=' || html[i + 1] != '"') return null
            i += 2

            val valueStart = i
            while (i < limit && html[i] != '"') {
                if (!isAllowedInValue(html[i])) return null
                i++
            }
            if (i >= limit) return null
            attributes[key] = html.substring(valueStart, i)
            i++
        }
    }

    private fun isNameChar(c: Char): Boolean = c.isAsciiLetter() || c in '0'..'9'

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** Inside a tag the only space is U+0020. VT, U+00A0, U+3000 and invisible marks are refused. */
    private fun isAllowedInValue(c: Char): Boolean {
        if (c == ' ') return true
        if (c == '<' || c == '>') return false
        if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)) return false
        return Character.getType(c) != Character.FORMAT.toInt()
    }

    fun normalize(text: String): String = text.replace(SPACE_RE, " ").trim()

    /**
     * Up to two literal fragments the saved body must contain: the start of the first text run
     * and the end of the last one. Runs with entities are skipped because the editor decodes them.
     * Expects HTML that [rejection] accepted.
     */
    fun samples(html: String): List<String> {
        val runs = textRuns(html).map(::normalize).filter { it.isNotEmpty() && '&' !in it }
        val pool = runs.filter { it.length >= MIN_SAMPLE_CHARS }.ifEmpty { runs }
        if (pool.isEmpty()) return emptyList()
        return listOf(
            safeEnds(pool.first().take(SAMPLE_CHARS)),
            safeEnds(pool.last().takeLast(SAMPLE_CHARS)),
        ).filter { it.isNotEmpty() }.distinct()
    }

    private fun textRuns(html: String): List<String> {
        val runs = ArrayList<String>()
        var at = 0
        while (at < html.length) {
            val open = html.indexOf('<', at)
            if (open < 0) {
                runs += html.substring(at)
                break
            }
            runs += html.substring(at, open)
            at = scanTag(html, open)?.end ?: break
        }
        return runs
    }

    /** A cut may split a surrogate pair; a lone half would never match the page text. */
    private fun safeEnds(text: String): String =
        text.dropWhile { it.isLowSurrogate() }.dropLastWhile { it.isHighSurrogate() }.trim()
}
