package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * The routine's central promise: it cannot press anything that publishes.
 *
 * Two layers. The golden pin stops any change to any script, whatever it looks like, until a
 * reviewer has seen the new text in `src/test/resources/draft-scripts`. The label checks explain
 * why a change is wrong, and also cover comments in the source file.
 */
class SaveDraftScriptsTest {

    private val scriptFile = File("src/main/kotlin/ai/closepaw/draft/DraftScript.kt")
    private val browserFile = File("src/main/kotlin/ai/closepaw/draft/ChromeDraftBrowser.kt")
    private val goldenDir = File("src/test/resources/draft-scripts")

    private val labels = listOf(
        "publish", "submit", "send", "continue", "next", "schedule", "subscribe", "confirm", "share", "post",
        "proceed", "deploy", "release", "go live",
        "公開", "投稿", "送信", "予約", "続ける", "次へ", "確認", "発行", "配信", "シェア",
    )

    /** Case-insensitive substrings, so a longer form of a word is caught too. */
    private fun forbiddenLabels(text: String): List<String> {
        val lower = text.lowercase()
        return labels.filter { it in lower }
    }

    /** Any way to press something other than the one `.click()`, or to reach it indirectly. */
    private fun indirectActions(source: String): List<String> {
        val found = mutableListOf<String>()
        if (Regex("""\[\s*['"`]""").containsMatchIn(source)) found += "bracket property"
        if (Regex("""['"`]\s*\+\s*['"`]""").containsMatchIn(source)) found += "split string"
        if (Regex("""new\s+(Mouse|Pointer|Keyboard|Touch|Submit|Input)\w*Event""", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
            found += "input event"
        }
        return found
    }

    private fun clickCalls(source: String): Int =
        Regex("""click\s*\(""", RegexOption.IGNORE_CASE).findAll(source).count()

    @Test
    fun `the scanners catch what they claim to`() {
        assertThat(forbiddenLabels("Publishing soon")).contains("publish")
        assertThat(forbiddenLabels("公開する")).contains("公開")
        assertThat(forbiddenLabels("form.requestSubmit()")).contains("submit")
        assertThat(forbiddenLabels("SEND it")).contains("send")
        assertThat(indirectActions("button['click']()")).contains("bracket property")
        assertThat(indirectActions("""button["click"]()""")).contains("bracket property")
        assertThat(indirectActions("b['cl' + 'ick']()")).contains("split string")
        assertThat(indirectActions("new MouseEvent('x')")).contains("input event")
        assertThat(indirectActions("new KeyboardEvent('x')")).contains("input event")
        assertThat(clickCalls("a.CLICK ()")).isEqualTo(1)
    }

    @Test
    fun `every script matches its pinned text exactly`() {
        val pinned = goldenDir.listFiles().orEmpty().map { it.nameWithoutExtension }
        assertThat(pinned).containsExactlyElementsIn(DraftScript.values().map { it.name })
        DraftScript.values().forEach { script ->
            val golden = File(goldenDir, "${script.name}.js").readText(Charsets.UTF_8)
            assertThat(script.source.replace("\r\n", "\n").trim()).isEqualTo(golden.replace("\r\n", "\n").trim())
        }
    }

    @Test
    fun `no script names a forbidden label`() {
        DraftScript.values().forEach { script ->
            assertThat(forbiddenLabels(script.source)).isEmpty()
        }
    }

    @Test
    fun `the script source file names no forbidden label, comments included`() {
        assertThat(forbiddenLabels(scriptFile.readText(Charsets.UTF_8))).isEmpty()
    }

    @Test
    fun `no script has an indirect route to an action`() {
        DraftScript.values().forEach { script ->
            assertThat(indirectActions(script.source)).isEmpty()
        }
    }

    @Test
    fun `the only click is the note save label`() {
        DraftScript.values().forEach { script ->
            val expected = if (script == DraftScript.NOTE_SAVE_CLICK) 1 else 0
            assertThat(clickCalls(script.source)).isEqualTo(expected)
        }
        assertThat(NOTE_SAVE_LABEL).isEqualTo("下書き保存")
        assertThat(DraftScript.NOTE_SAVE_CLICK.source).contains("textContent.trim() === '下書き保存'")
    }

    @Test
    fun `no script can act on the page in another way`() {
        val banned = listOf(
            "new XMLHttpRequest", "beacon", "method:", "location.assign", "location.href =", "location.replace",
            "window.open", "eval(", "Function(", "document.write", "innerHTML", "setAttribute", "dispatchEvent(new M",
        )
        DraftScript.values().forEach { script ->
            banned.forEach { word -> assertThat(script.source.lowercase()).doesNotContain(word.lowercase()) }
        }
    }

    @Test
    fun `the only events are paste, input and change`() {
        val types = DraftScript.values().flatMap { script ->
            Regex("""new\s+(\w*Event)\(\s*'(\w+)'""").findAll(script.source).map { it.value }.toList()
        }.map { it.substringAfter("'").substringBefore("'") }.toSet()
        assertThat(types).containsExactly("paste", "input", "change")
    }

    @Test
    fun `network reads are default GETs from the two save probes`() {
        val fetchers = DraftScript.values().filter { "fetch(" in it.source }
        assertThat(fetchers).containsExactly(DraftScript.SUBSTACK_SAVED_PROBE, DraftScript.NOTE_SAVED_PROBE)
    }

    @Test
    fun `every script that changes the page checks the draft URL first`() {
        val guarded = listOf(
            DraftScript.SET_TITLE, DraftScript.CLIPBOARD_WRITE, DraftScript.INSERT_HTML, DraftScript.FOCUS_TARGET,
            DraftScript.ANCHOR_SELECT, DraftScript.PASTE_IMAGE_HERE, DraftScript.CARET_AFTER_IMAGES,
            DraftScript.FOCUS_BODY, DraftScript.COVER_FILE,
            DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE,
            DraftScript.NOTE_SAVE_CLICK, DraftScript.READ_BACK, DraftScript.NOTE_SAVED_PROBE,
            DraftScript.SUBSTACK_SAVED_PROBE,
        )
        val guard = "if (location.href !== a.editorUrl) return { ok: false, moved: true };"
        guarded.forEach { script ->
            assertThat(script.source.substringAfter("function (a) {").trim()).startsWith(guard)
        }
    }

    @Test
    fun `the note save check does not read the article as a signal`() {
        val source = DraftScript.NOTE_SAVED_PROBE.source
        assertThat(source).doesNotContain("innerText")
        assertThat(source).contains("editor.contains(el)")
        assertThat(source).contains("entry.responseStatus >= 200 && entry.responseStatus < 300")
        assertThat(source).contains("entry.initiatorType !== 'fetch' && entry.initiatorType !== 'xmlhttprequest'")
    }

    @Test
    fun `chrome is driven by exactly these CDP commands`() {
        val sent = Regex("\\.send\\(\\s*\"([^\"]+)\"").findAll(browserFile.readText()).map { it.groupValues[1] }.toSet()
        assertThat(sent).containsExactly(
            "Page.navigate", "Runtime.evaluate", "Target.createTarget", "Target.closeTarget",
            "Page.bringToFront", "Input.dispatchMouseEvent", "Input.dispatchKeyEvent",
        )
    }

    @Test
    fun `the only keys the run presses are paste, Backspace, Enter and Delete`() {
        val source = browserFile.readText()
        val keys = Regex("""put\("key", "([^"]+)"\)""").findAll(source).map { it.groupValues[1] }.toList()
        assertThat(keys).containsExactly("v")
        assertThat(DraftKey.values().map { it.key }).containsExactly("Backspace", "Enter", "Delete")
        assertThat(source).contains("put(\"commands\", buildJsonArray { add(JsonPrimitive(\"paste\")) })")
    }

    @Test
    fun `the draft run never removes tasks from the display`() {
        val source = browserFile.readText()
        assertThat(source).contains("display?.stopKeepingTasks()")
        assertThat(source).doesNotContain("display?.stop()")
    }

    @Test
    fun `request data reaches a script only as JSON`() {
        val hostile = "'); document.body.remove(); ('"
        val expression = DraftScript.SET_TITLE.expression(buildJsonObject { put("title", hostile) })
        assertThat(expression).endsWith(")({\"title\":\"$hostile\"})")
        assertThat(expression).startsWith("(function (a)")
    }

    @Test
    fun `an evaluate that threw is an error, not an empty result`() {
        val threw = buildJsonObject {
            put("result", buildJsonObject { put("type", "object") })
            put("exceptionDetails", buildJsonObject { put("text", "boom") })
        }
        val failure = runCatching { draftEvaluateResult(DraftScript.READ_BACK, threw) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(DraftScriptException::class.java)
    }
}
