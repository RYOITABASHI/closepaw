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

    private fun onSubstack(html: String, hosts: Set<String> = emptySet()) =
        ArticleHtmlPolicy.rejection(html, DraftPlatform.SUBSTACK, hosts)

    private fun onNote(html: String) = ArticleHtmlPolicy.rejection(html, DraftPlatform.NOTE)

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

    // ── HTML: structure ──

    @Test
    fun `plain article markup passes`() {
        val html = """<h2>Head</h2><p>Text with <a href="https://example.com/a?x=1&amp;y=2">a link</a>, <strong>bold</strong> &amp; more.</p>""" +
            """<ul><li>One</li></ul><br/><hr />"""
        assertThat(onSubstack(html)).isNull()
        assertThat(onNote(html)).isNull()
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
            "<p/onclick=\"x()\">t</p>",
            "<p>t<",
            "a < b",
            "<p style=\"color:red\">t</p>",
            "<p\nonclick='x()'>t</p>",
            "<a href=\"https://e.com\"href=\"https://f.com\">t</a>",
            "<a href=\"https://e.com\" href=\"https://f.com\">t</a>",
            "<a href='https://e.com'>t</a>",
        ).forEach { assertThat(onSubstack(it)).isNotNull() }
    }

    @Test
    fun `a body with no text is refused`() {
        assertThat(onSubstack("<p> </p><br/>")).isEqualTo("html-no-text")
    }

    // ── HTML: whitespace agrees with the tokenizer ──

    @Test
    fun `whitespace a browser does not count ends no tag`() {
        listOf("\u000B", " ", "　", " ", "\u0085").forEach { space ->
            assertThat(onSubstack("<p${space}onclick=\"x()\">t</p>")).isNotNull()
            assertThat(onSubstack("<a${space}href=\"https://e.com\">t</a>")).isNotNull()
            assertThat(onSubstack("<a href=\"https://e.com\"${space}>t</a>")).isNotNull()
            assertThat(onSubstack("<br$space/>t")).isNotNull()
        }
    }

    @Test
    fun `the HTML tokenizer's own whitespace is accepted`() {
        listOf(" ", "\t", "\n", "\u000C", "\r").forEach { space ->
            assertThat(onSubstack("<a${space}href=\"https://e.com\"$space>t</a>")).isNull()
        }
    }

    @Test
    fun `those characters are refused inside alt values and URLs too`() {
        val hosts = setOf("img.example.com")
        val ok = "<img src=\"https://img.example.com/a.jpg\" alt=\"a b\">t"
        assertThat(onSubstack(ok, hosts)).isNull()
        listOf("\u000B", " ", "　", "​", "‮", "\t", "\n").forEach { char ->
            val html = "<img src=\"https://img.example.com/a.jpg\" alt=\"a${char}b\">t"
            assertThat(onSubstack(html, hosts)).isEqualTo("html-unreadable-tag")
            assertThat(onSubstack("<a href=\"https://e.com/${char}x\">t</a>")).isNotNull()
        }
    }

    // ── HTML: hostile size ──

    @Test
    fun `thousands of attributes neither overflow nor pass`() {
        val many = " a=\"b\"".repeat(50_000)
        assertThat(onSubstack("<p$many>t</p>")).isEqualTo("html-unreadable-tag")
        val manyValid = " href=\"https://e.com\"".repeat(50_000)
        assertThat(onSubstack("<a$manyValid>t</a>")).isEqualTo("html-unreadable-tag")
    }

    @Test
    fun `a tag longer than the limit is refused, and so is a huge value`() {
        assertThat(onSubstack("<a href=\"https://e.com/${"x".repeat(5_000)}\">t</a>")).isEqualTo("html-unreadable-tag")
        assertThat(onSubstack("<p${" ".repeat(5_000)}>t</p>")).isEqualTo("html-unreadable-tag")
        assertThat(onSubstack("<a href=\"https://e.com/${"x".repeat(2_000)}\">t</a>")).isNull()
    }

    @Test
    fun `a megabyte of tags scans in linear time`() {
        val html = "<p>t</p>".repeat(130_000)
        assertThat(onSubstack(html)).isNull()
        assertThat(ArticleHtmlPolicy.samples("<p>first of many paragraphs</p>" + html)).isNotEmpty()
    }

    // ── HTML: images ──

    @Test
    fun `note refuses every img, whatever its host`() {
        assertThat(onNote("<img src=\"https://img.example.com/a.jpg\">t")).isEqualTo("html-image-not-allowed")
        assertThat(ArticleHtmlPolicy.rejection("<img src=\"https://img.example.com/a.jpg\">t", DraftPlatform.NOTE, setOf("img.example.com")))
            .isEqualTo("html-image-not-allowed")
    }

    @Test
    fun `substack refuses img by default and takes exact hosts only`() {
        val html = "<img src=\"https://img.example.com/a.jpg\">t"
        assertThat(onSubstack(html)).isEqualTo("html-image-not-allowed")
        assertThat(onSubstack(html, setOf("img.example.com"))).isNull()
        assertThat(onSubstack(html, setOf("example.com"))).isEqualTo("html-image-not-allowed")
        assertThat(onSubstack("<img src=\"https://sub.img.example.com/a.jpg\">t", setOf("img.example.com"))).isNotNull()
        assertThat(onSubstack("<img alt=\"x\">t", setOf("img.example.com"))).isNotNull()
    }

    @Test
    fun `an image on a signed-in site is refused even if the caller lists it`() {
        val listed = setOf("example-pub.substack.com", "note.com")
        assertThat(onSubstack("<img src=\"https://example-pub.substack.com/a.jpg\">t", listed)).isNotNull()
        assertThat(onSubstack("<img src=\"https://note.com/a.jpg\">t", listed)).isNotNull()
        assertThat(ArticleHtmlPolicy.isAllowableImageHost("substack.com")).isFalse()
        assertThat(ArticleHtmlPolicy.isAllowableImageHost("x.note.com")).isFalse()
        assertThat(ArticleHtmlPolicy.isAllowableImageHost("img.example.com")).isTrue()
    }

    // ── HTML: links ──

    @Test
    fun `links to signed-in sites must be plain article links`() {
        listOf(
            "https://other-pub.substack.com/p/some-article",
            "https://other-pub.substack.com/p/some-article/",
            "https://note.com/creator_1/n/nabc123def",
        ).forEach { assertThat(onSubstack("<a href=\"$it\">t</a>")).isNull() }

        listOf(
            "https://substack.com",
            "https://substack.com/p/x",
            "https://other-pub.substack.com/",
            "https://other-pub.substack.com/publish/post/1",
            "https://other-pub.substack.com/api/v1/drafts/1",
            "https://other-pub.substack.com/p/x?utm=1",
            "https://other-pub.substack.com/p/x#a",
            "https://other-pub.substack.com/p/x/y",
            "https://other-pub.substack.com/p/x&#63;token=1",
            "https://other-pub.substack.com/p/x&#x3f;token=1",
            "https://note.com/",
            "https://note.com/notes/new",
            "https://note.com/api/v1/x",
            "https://note.com/creator/n/nabc?x=1",
            "https://editor.note.com/notes/nabc/edit/",
            "https://x.note.com/creator/n/nabc",
        ).forEach { assertThat(onSubstack("<a href=\"$it\">t</a>")).isEqualTo("html-link-not-allowed") }
    }

    @Test
    fun `lookalike hosts are not the signed-in sites`() {
        assertThat(onSubstack("<a href=\"https://notsubstack.com/x?y=1\">t</a>")).isNull()
        assertThat(onSubstack("<a href=\"https://note.com.example.org/a?b=1\">t</a>")).isNull()
    }

    @Test
    fun `a link cannot hide its host`() {
        listOf(
            "https://note.com@evil.test/",
            "https://evil.test:8443/",
            "https://127.0.0.1/",
            "https://192.168.1.1/x",
            "https://localhost/",
            "https://e.com./",
            "https://E.COM/",
            "https://note.com&#47;notes/new",
            "https://e&#46;com/",
            "https:\\\\e.com/",
            "https://e.com\\x",
            "https://",
        ).forEach { assertThat(onSubstack("<a href=\"$it\">t</a>")).isEqualTo("html-link-not-allowed") }
    }

    // ── Samples ──

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
        imageHosts: JsonArray? = null,
        extra: Pair<String, String>? = null,
    ): String = JsonObject(
        buildMap {
            put("version", JsonPrimitive(1))
            put("title", JsonPrimitive(title))
            put("html", JsonPrimitive(html))
            images?.let { put("images", it) }
            imageHosts?.let { put("image_hosts", it) }
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
    fun `image hosts come from the file and must be ordinary exact hosts`() {
        val html = "<img src=\"https://img.example.com/a.jpg\">text here"
        val hosts = JsonArray(listOf(JsonPrimitive("img.example.com")))
        assertThat(reason(substack, file(html = html))).isEqualTo("html-image-not-allowed")
        assertThat(reason(substack, file(html = html, imageHosts = hosts))).isNull()
        assertThat(reason(note, file(html = html, imageHosts = hosts))).isEqualTo("html-image-not-allowed")
        listOf("*.example.com", "note.com", "x.substack.com", "https://e.com", "localhost", "1.2.3.4").forEach {
            assertThat(reason(substack, file(html = html, imageHosts = JsonArray(listOf(JsonPrimitive(it))))))
                .isEqualTo("image-hosts-invalid")
        }
    }

    @Test
    fun `malformed files are refused with a reason`() {
        assertThat(reason(note, "not json")).isEqualTo("file-not-json")
        assertThat(reason(note, "[]")).isEqualTo("file-not-json")
        assertThat(reason(note, file(extra = "goal" to "do something else"))).isEqualTo("file-unknown-key")
        assertThat(reason(note, """{"version":2,"title":"t","html":"<p>t</p>"}""")).isEqualTo("file-version")
        assertThat(reason(note, """{"version":"1","title":"t","html":"<p>t</p>"}""")).isEqualTo("file-version")
        assertThat(reason(note, file(title = "  "))).isEqualTo("title-invalid")
        assertThat(reason(note, file(title = "two\nlines"))).isEqualTo("title-invalid")
        assertThat(reason(note, file(title = "x".repeat(301)))).isEqualTo("title-invalid")
        assertThat(reason(note, file(html = "<script>x</script>"))).isEqualTo("html-tag-not-allowed")
    }

    @Test
    fun `a null or a number is not a string`() {
        val body = "\"html\":\"<p>Some body text here</p>\""
        assertThat(reason(note, """{"version":1,"title":null,$body}""")).isEqualTo("title-invalid")
        assertThat(reason(note, """{"version":1,"title":123,$body}""")).isEqualTo("title-invalid")
        assertThat(reason(note, """{"version":1,"title":true,$body}""")).isEqualTo("title-invalid")
        assertThat(reason(note, """{"version":1,"title":"t","html":null}""")).isEqualTo("html-invalid")
        val nullBase64 = """{"name":"a.jpg","mime":"image/jpeg","base64":null}"""
        assertThat(reason(note, """{"version":1,"title":"t",$body,"images":[$nullBase64]}""")).isEqualTo("images-invalid")
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
