package ai.closepaw.draft

/** The only editors the routine can reach. Nothing else is expressible in a request. */
enum class DraftPlatform(val wire: String) {
    SUBSTACK("substack"),
    NOTE("note");

    companion object {
        fun fromWire(value: String?): DraftPlatform? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Fixed URLs for one editor. Hosts come from [DraftPlatform]; the only variable part is the
 * Substack subdomain, which must match [SUBDOMAIN_RE] and is never parsed as a URL.
 */
class DraftSite private constructor(
    val platform: DraftPlatform,
    val draftListUrl: String,
    val newDraftUrl: String,
    private val listPrefix: String,
    private val editorRe: Regex,
) {
    fun isListUrl(url: String): Boolean = url.startsWith(listPrefix)

    fun isEditorUrl(url: String): Boolean = editorRe.matches(url)

    /** The draft's id (Substack) or key (note), or null if [url] is not this site's editor. */
    fun draftId(url: String): String? = editorRe.matchEntire(url)?.groupValues?.get(1)

    companion object {
        val SUBDOMAIN_RE = Regex("^[a-z0-9-]+$")
        private const val MAX_SUBDOMAIN_CHARS = 63

        /** Null when the pair is not valid: Substack needs a subdomain, note must not have one. */
        fun of(platform: DraftPlatform, subdomain: String?): DraftSite? = when (platform) {
            DraftPlatform.SUBSTACK -> subdomain
                ?.takeIf { it.length <= MAX_SUBDOMAIN_CHARS && SUBDOMAIN_RE.matches(it) }
                ?.let { substack(it) }
            DraftPlatform.NOTE -> if (subdomain == null) NOTE_SITE else null
        }

        private fun substack(subdomain: String): DraftSite {
            val origin = "https://$subdomain.substack.com"
            return DraftSite(
                platform = DraftPlatform.SUBSTACK,
                draftListUrl = "$origin/publish/posts/drafts",
                newDraftUrl = "$origin/publish/post?type=newsletter",
                listPrefix = "$origin/publish/posts/drafts",
                // The subdomain alphabet has no regex metacharacters.
                editorRe = Regex("^https://$subdomain\\.substack\\.com/publish/post/([0-9]+)(?:[/?#].*)?$"),
            )
        }

        private val NOTE_SITE = DraftSite(
            platform = DraftPlatform.NOTE,
            draftListUrl = "https://note.com/notes?status=draft",
            newDraftUrl = "https://note.com/notes/new",
            listPrefix = "https://note.com/notes",
            editorRe = Regex("^https://editor\\.note\\.com/notes/([A-Za-z0-9]+)/edit/?(?:[?#].*)?$"),
        )
    }
}
