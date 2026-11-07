package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * The routine's central promise: it cannot press anything that publishes. This test fails if a
 * publish-like label shows up anywhere in the script source, comments included.
 */
class SaveDraftScriptsTest {

    private val scriptFile = File("src/main/kotlin/ai/closepaw/draft/DraftScript.kt")
    private val browserFile = File("src/main/kotlin/ai/closepaw/draft/ChromeDraftBrowser.kt")

    private val asciiLabels = Regex(
        "\\b(publish|post|send|continue|next|schedule|submit|share|subscribe|confirm|go live)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val japaneseLabels = listOf("公開", "投稿", "送信", "予約", "続ける", "次へ", "確認", "発行", "配信", "シェア")

    private fun publishLabels(text: String): List<String> =
        asciiLabels.findAll(text).map { it.value }.toList() + japaneseLabels.filter { it in text }

    @Test
    fun `the scanner itself finds a publish label`() {
        assertThat(publishLabels("if (b.textContent === 'Publish now') b.click();")).contains("Publish")
        assertThat(publishLabels("button === '公開に進む'")).contains("公開")
        assertThat(publishLabels("Send to everyone now")).contains("Send")
    }

    @Test
    fun `no script names a publish label`() {
        DraftScript.values().forEach { script ->
            assertThat(publishLabels(script.source)).isEmpty()
        }
    }

    @Test
    fun `the script source file names no publish label, comments included`() {
        assertThat(publishLabels(scriptFile.readText())).isEmpty()
    }

    @Test
    fun `the only click is the note save label`() {
        val clickers = DraftScript.values().filter { ".click(" in it.source }
        assertThat(clickers).containsExactly(DraftScript.NOTE_SAVE_CLICK)
        assertThat(Regex("\\.click\\(").findAll(scriptFile.readText()).count()).isEqualTo(1)
        assertThat(NOTE_SAVE_LABEL).isEqualTo("下書き保存")
        assertThat(DraftScript.NOTE_SAVE_CLICK.source).contains("textContent.trim() === '下書き保存'")
    }

    @Test
    fun `no script can act on the page in another way`() {
        val banned = listOf(
            "submit", "KeyboardEvent", "MouseEvent", "PointerEvent", "XMLHttpRequest", "sendBeacon",
            "method:", "location.assign", "location.href =", "location.replace", "window.open", "eval(",
        )
        DraftScript.values().forEach { script ->
            banned.forEach { word -> assertThat(script.source).doesNotContain(word) }
        }
    }

    @Test
    fun `the only network read is one default fetch, on Substack`() {
        val fetchers = DraftScript.values().filter { "fetch(" in it.source }
        assertThat(fetchers).containsExactly(DraftScript.SUBSTACK_SAVED_PROBE)
    }

    @Test
    fun `chrome is driven by exactly these CDP commands`() {
        val sent = Regex("\\.send\\(\\s*\"([^\"]+)\"").findAll(browserFile.readText()).map { it.groupValues[1] }.toSet()
        assertThat(sent).containsExactly("Page.navigate", "Runtime.evaluate", "Target.closeTarget")
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
