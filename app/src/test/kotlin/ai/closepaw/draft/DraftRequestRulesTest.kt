package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class DraftRequestRulesTest {

    private val substack = DraftSite.of(DraftPlatform.SUBSTACK, "example-pub")!!
    private val note = DraftSite.of(DraftPlatform.NOTE, null)!!

    // ── Sites ──

    @Test
    fun `hosts are built from the enum and the subdomain only`() {
        assertThat(substack.newDraftUrl).isEqualTo("https://example-pub.substack.com/publish/post?type=newsletter")
        assertThat(substack.draftListUrl).startsWith("https://example-pub.substack.com/")
        assertThat(note.newDraftUrl).isEqualTo("https://note.com/notes/new")
    }

    @Test
    fun `a subdomain outside the alphabet builds no site`() {
        listOf(
            "", "UPPER", "a.b", "a/b", "evil.com#", "a b", "a_b", "a\n", "../x", "a%2eb", "é", "x".repeat(64),
        ).forEach { assertThat(DraftSite.of(DraftPlatform.SUBSTACK, it)).isNull() }
        assertThat(DraftSite.of(DraftPlatform.SUBSTACK, null)).isNull()
    }

    @Test
    fun `note takes no subdomain and an unknown platform is nothing`() {
        assertThat(DraftSite.of(DraftPlatform.NOTE, "anything")).isNull()
        assertThat(DraftPlatform.fromWire("medium")).isNull()
        assertThat(DraftPlatform.fromWire(null)).isNull()
        assertThat(DraftPlatform.fromWire("substack")).isEqualTo(DraftPlatform.SUBSTACK)
    }

    @Test
    fun `only the site's own editor URL counts`() {
        assertThat(substack.isEditorUrl("https://example-pub.substack.com/publish/post/12345")).isTrue()
        assertThat(substack.draftId("https://example-pub.substack.com/publish/post/12345?x=1")).isEqualTo("12345")
        listOf(
            "https://other.substack.com/publish/post/12345",
            "https://example-pub.substack.com.evil.test/publish/post/12345",
            "https://example-pub.substack.com/publish/post?type=newsletter",
            "http://example-pub.substack.com/publish/post/12345",
            "https://substack.com/sign-in",
        ).forEach { assertThat(substack.isEditorUrl(it)).isFalse() }

        assertThat(note.isEditorUrl("https://editor.note.com/notes/n0a1b2c3d/edit/")).isTrue()
        assertThat(note.draftId("https://editor.note.com/notes/n0a1b2c3d/edit/")).isEqualTo("n0a1b2c3d")
        assertThat(note.isEditorUrl("https://note.com/login")).isFalse()
        assertThat(note.isEditorUrl("https://editor.note.com/notes/n0a1b2c3d/publish/")).isFalse()
    }

    // ── HTML ──

    @Test
    fun `plain article markup passes`() {
        val html = """<h2>Head</h2><p>Text with <a href="https://example.com/a?x=1&amp;y=2">a link</a>, <strong>bold</strong> &amp; more.</p>""" +
            """<ul><li>One</li></ul><figure><img src="https://example.com/a.jpg" alt="x"><figcaption>c</figcaption></figure><br/>"""
        assertThat(ArticleHtmlPolicy.rejection(html)).isNull()
    }

    @Test
    fun `anything that could run in the editor origin is refused`() {
        listOf(
            "<script>x()</script><p>t</p>",
            "<p onclick=\"x()\">t</p>",
            "<img src=\"https://e.com/a.png\" onerror=\"x()\">t",
            "<img src=x onerror=x>t",
            "<a href=\"javascript:x()\">t</a>",
            "<a href=\" javascript:x()\">t</a>",
            "<a href=\"data:text/html,x\">t</a>",
            "<a href=\"http://example.com\">t</a>",
            "<img src=\"data:image/png;base64,AA\">t",
            "<svg onload=\"x()\"></svg>t",
            "<iframe src=\"https://e.com\"></iframe>t",
            "<form><button>t</button></form>",
            "<style>*{}</style>t",
            "<!-- c --><p>t</p>",
            "<p>t</p onclick=\"x()\">",
            "<p>t<",
            "a < b",
            "<p style=\"color:red\">t</p>",
            "<p\nonclick='x()'>t</p>",
        ).forEach { assertThat(ArticleHtmlPolicy.rejection(it)).isNotNull() }
    }

    @Test
    fun `a body with no text is refused`() {
        assertThat(ArticleHtmlPolicy.rejection("<p> </p><br/>")).isEqualTo("html-no-text")
    }

    @Test
    fun `samples come from the first and last text runs`() {
        val samples = ArticleHtmlPolicy.samples("<h2>Hi</h2><p>The first paragraph of text.</p><p>The  closing\nparagraph here.</p>")
        assertThat(samples).containsExactly("The first paragraph of text.", "The closing paragraph here.").inOrder()
    }

    @Test
    fun `samples skip entities and never split a surrogate pair`() {
        assertThat(ArticleHtmlPolicy.samples("<p>a &amp; b, long enough</p><p>clean run of text</p>")).containsExactly("clean run of text")
        val emoji = "😀".repeat(25)
        ArticleHtmlPolicy.samples("<p>$emoji</p>").forEach { sample ->
            assertThat(Character.isLowSurrogate(sample.first())).isFalse()
            assertThat(Character.isHighSurrogate(sample.last())).isFalse()
        }
    }

    // ── File ──

    private val image = buildJsonObject {
        put("name", "header.jpg")
        put("mime", "image/jpeg")
        put("base64", Base64.getEncoder().encodeToString(ByteArray(8)))
    }

    private fun file(
        title: String = "A title",
        html: String = "<p>Some body text here</p>",
        images: JsonArray? = null,
        extra: Pair<String, String>? = null,
    ): String = JsonObject(
        buildMap {
            put("version", JsonPrimitive(1))
            put("title", JsonPrimitive(title))
            put("html", JsonPrimitive(html))
            images?.let { put("images", it) }
            extra?.let { put(it.first, JsonPrimitive(it.second)) }
        },
    ).toString()

    private fun reason(site: DraftSite, text: String): String? =
        (SaveDraftRequestParser.parse(site, text) as? SaveDraftRequestParser.Parsed.Bad)?.reason

    @Test
    fun `a good file parses, and images are kept for note only`() {
        val text = file(images = JsonArray(listOf(image)))
        val onNote = SaveDraftRequestParser.parse(note, text) as SaveDraftRequestParser.Parsed.Ok
        assertThat(onNote.request.images).hasSize(1)
        assertThat(onNote.request.samples).containsExactly("Some body text here")
        val onSubstack = SaveDraftRequestParser.parse(substack, text) as SaveDraftRequestParser.Parsed.Ok
        assertThat(onSubstack.request.images).isEmpty()
    }

    @Test
    fun `malformed files are refused with a reason`() {
        assertThat(reason(note, "not json")).isEqualTo("file-not-json")
        assertThat(reason(note, "[]")).isEqualTo("file-not-json")
        assertThat(reason(note, file(extra = "goal" to "do something else"))).isEqualTo("file-unknown-key")
        assertThat(reason(note, """{"version":2,"title":"t","html":"<p>t</p>"}""")).isEqualTo("file-version")
        assertThat(reason(note, file(title = "  "))).isEqualTo("title-invalid")
        assertThat(reason(note, file(title = "two\nlines"))).isEqualTo("title-invalid")
        assertThat(reason(note, file(title = "x".repeat(301)))).isEqualTo("title-invalid")
        assertThat(reason(note, file(html = "<script>x</script>"))).isEqualTo("html-tag-not-allowed")
    }

    @Test
    fun `bad images are refused`() {
        val bad = listOf(
            buildJsonObject { put("name", "../x.jpg"); put("mime", "image/jpeg"); put("base64", "AAAA") },
            buildJsonObject { put("name", "x.svg"); put("mime", "image/svg+xml"); put("base64", "AAAA") },
            buildJsonObject { put("name", "x.jpg"); put("mime", "image/jpeg"); put("base64", "@@@") },
            buildJsonObject { put("name", "x.jpg"); put("mime", "image/jpeg"); put("base64", "") },
            buildJsonObject { put("name", "x.jpg"); put("mime", "image/jpeg"); put("base64", "AAAA"); put("path", "/x") },
        )
        bad.forEach { assertThat(reason(note, file(images = JsonArray(listOf(it))))).isEqualTo("images-invalid") }
        assertThat(reason(note, file(images = JsonArray(List(5) { image })))).isEqualTo("images-invalid")
    }
}
