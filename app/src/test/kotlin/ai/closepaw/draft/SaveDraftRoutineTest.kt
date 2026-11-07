package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SaveDraftRoutineTest {

    private val substack = DraftSite.of(DraftPlatform.SUBSTACK, "example-pub")!!
    private val note = DraftSite.of(DraftPlatform.NOTE, null)!!
    private val substackEditor = "https://example-pub.substack.com/publish/post/123"
    private val noteEditor = "https://editor.note.com/notes/nabc/edit/"
    private val image = DraftImage("a.jpg", "image/jpeg", "AAAA")

    private fun request(site: DraftSite, images: List<DraftImage> = emptyList()) =
        SaveDraftRequest(site, "A title", "<p>Body text here</p>", listOf("Body text here"), images)

    /** A scripted Chrome. Each test overrides only what makes its case. */
    private class FakeBrowser(
        val listUrl: String,
        var editorUrl: String,
        val preexisting: List<DraftTab> = emptyList(),
    ) : DraftBrowser {
        val opened = mutableListOf<String>()
        val navigated = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val scripts = mutableListOf<DraftScript>()
        val evaluatedTabs = mutableSetOf<String>()
        var tabsOpen = 0
        var listUrlAfterOpen = listUrl
        var navigatedTo: String? = null
        var listFound = false
        var listLink: String? = null
        var editorHasContent = false
        var readBackOk = true
        var savedResponses = true
        var saveLabelMatches = 1
        var pageImages = 0
        var imageRouteWorks: DraftScript? = null
        var hang: DraftScript? = null

        override suspend fun open(url: String) {
            opened += url
            tabsOpen++
        }

        override suspend fun tabs(): List<DraftTab> {
            val url = navigatedTo ?: listUrlAfterOpen
            return preexisting + (if (tabsOpen > 0) listOf(DraftTab("ours", url)) else emptyList())
        }

        override suspend fun navigate(tabId: String, url: String) {
            navigated += url
            navigatedTo = editorUrl
        }

        override suspend fun close(tabId: String) {
            closed += tabId
        }

        override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
            if (script == hang) CompletableDeferred<Unit>().await()
            evaluatedTabs += tabId
            scripts += script
            return when (script) {
                DraftScript.LIST_PROBE -> buildJsonObject {
                    put("found", listFound)
                    put("ready", true)
                    put("url", listLink)
                }
                DraftScript.EDITOR_PROBE -> buildJsonObject {
                    put("href", navigatedTo)
                    put("titleFound", true)
                    put("bodyFound", true)
                    put("titleEmpty", !editorHasContent)
                    put("bodyEmpty", !editorHasContent)
                }
                DraftScript.SET_TITLE, DraftScript.PASTE_HTML -> buildJsonObject { put("ok", true) }
                DraftScript.IMAGE_COUNT -> buildJsonObject { put("count", pageImages) }
                DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE -> {
                    if (script == imageRouteWorks) pageImages++
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.READ_BACK -> buildJsonObject {
                    put("href", editorUrl)
                    put("titleOk", readBackOk)
                    put("bodyOk", readBackOk)
                }
                DraftScript.NOTE_SAVE_CLICK -> buildJsonObject {
                    put("clicked", saveLabelMatches == 1)
                    put("matches", saveLabelMatches)
                    put("at", 12.5)
                }
                DraftScript.NOTE_SAVED_PROBE, DraftScript.SUBSTACK_SAVED_PROBE ->
                    buildJsonObject { put("saved", savedResponses) }
            }
        }

        val writes get() = scripts.filter {
            it in setOf(DraftScript.SET_TITLE, DraftScript.PASTE_HTML, DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE)
        }
    }

    private fun TestScope.routine(browser: DraftBrowser) =
        SaveDraftRoutine(browser, now = { testScheduler.currentTime })

    private fun substackBrowser() = FakeBrowser(substack.draftListUrl, substackEditor)

    private fun noteBrowser() = FakeBrowser(note.draftListUrl, noteEditor)

    @Test
    fun `substack saves by autosave and never clicks`() = runTest {
        val browser = substackBrowser()
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.url).isEqualTo(substackEditor)
        assertThat(result.titleOk && result.bodyOk && result.saved && result.wrote).isTrue()
        assertThat(result.dedupe).isEqualTo("not-seen")
        assertThat(result.tabClosed).isTrue()
        assertThat(browser.scripts).doesNotContain(DraftScript.NOTE_SAVE_CLICK)
        assertThat(browser.navigated).containsExactly(substack.newDraftUrl)
    }

    @Test
    fun `note clicks the save label exactly once`() = runTest {
        val browser = noteBrowser()
        val result = routine(browser).run(request(note))

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.url).isEqualTo(noteEditor)
        assertThat(browser.scripts.count { it == DraftScript.NOTE_SAVE_CLICK }).isEqualTo(1)
        assertThat(browser.scripts).doesNotContain(DraftScript.SUBSTACK_SAVED_PROBE)
    }

    @Test
    fun `an unconfirmed save is uncertain and the click is not repeated`() = runTest {
        val browser = noteBrowser().apply { savedResponses = false }
        val result = routine(browser).run(request(note))

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("save-unconfirmed")
        assertThat(result.url).isEqualTo(noteEditor)
        assertThat(browser.closed).isEmpty()
        assertThat(browser.scripts.count { it == DraftScript.NOTE_SAVE_CLICK }).isEqualTo(1)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(1)
    }

    @Test
    fun `no unique save control means no click`() = runTest {
        val browser = noteBrowser().apply { saveLabelMatches = 2 }
        val result = routine(browser).run(request(note))

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("save-control-not-unique")
    }

    @Test
    fun `a read-back mismatch is uncertain`() = runTest {
        val browser = substackBrowser().apply { readBackOk = false }
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("read-back-mismatch")
    }

    @Test
    fun `a same-title draft stops the run before any write`() = runTest {
        val browser = substackBrowser().apply {
            listFound = true
            listLink = "https://example-pub.substack.com/publish/post/77"
        }
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.ALREADY_EXISTS)
        assertThat(result.url).isEqualTo("https://example-pub.substack.com/publish/post/77")
        assertThat(result.wrote).isFalse()
        assertThat(browser.navigated).isEmpty()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `a duplicate link off the site is not returned`() = runTest {
        val browser = substackBrowser().apply {
            listFound = true
            listLink = "https://evil.test/x"
        }
        val result = routine(browser).run(request(substack))
        assertThat(result.url).isEqualTo(substack.draftListUrl)
    }

    @Test
    fun `a redirect away from the editor writes nothing`() = runTest {
        val browser = FakeBrowser(substack.draftListUrl, "https://substack.com/sign-in")
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("editor-not-reached")
        assertThat(result.wrote).isFalse()
        assertThat(result.url).isNull()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `an editor that is not empty is left alone`() = runTest {
        val browser = substackBrowser().apply { editorHasContent = true }
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.FAILED)
        assertThat(result.reason).isEqualTo("editor-not-empty")
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `a tab that was already open is never touched`() = runTest {
        val theirs = DraftTab("theirs", substack.draftListUrl)
        val browser = FakeBrowser(substack.draftListUrl, substackEditor, listOf(theirs))
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.evaluatedTabs).containsExactly("ours")
    }

    @Test
    fun `a signed-out list falls through and the editor check decides`() = runTest {
        val browser = FakeBrowser(substack.draftListUrl, substackEditor).apply {
            listUrlAfterOpen = "https://substack.com/sign-in"
        }
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("tab-not-found")
        assertThat(result.dedupe).isEqualTo("unknown")
        assertThat(browser.opened).containsExactly(substack.draftListUrl, substack.newDraftUrl).inOrder()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `a hang after writing is uncertain and nothing is retried`() = runTest {
        val browser = substackBrowser().apply { hang = DraftScript.READ_BACK }
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.outcome.status).isEqualTo("uncertain")
        assertThat(result.reason).isEqualTo("timeout")
        assertThat(result.wrote).isTrue()
        assertThat(browser.scripts.count { it == DraftScript.SET_TITLE }).isEqualTo(1)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(1)
        assertThat(browser.navigated).hasSize(1)
    }

    @Test
    fun `a hang before writing is also uncertain, and says nothing was written`() = runTest {
        val browser = substackBrowser().apply { hang = DraftScript.EDITOR_PROBE }
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("timeout")
        assertThat(result.wrote).isFalse()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `stopped mid-run reports what is known`() = runTest {
        val browser = substackBrowser().apply { hang = DraftScript.READ_BACK }
        val routine = routine(browser)
        val job = launch { routine.run(request(substack)) }
        advanceTimeBy(60_000L) // Past the list check, into the hung read-back.
        runCurrent()
        assertThat(browser.writes).isNotEmpty()
        job.cancel()
        advanceUntilIdle()
        assertThat(routine.resultSoFar("stopped").outcome).isEqualTo(DraftOutcome.UNCERTAIN)
    }

    @Test
    fun `a stop before writing is a plain failure`() = runTest {
        val browser = substackBrowser().apply { hang = DraftScript.LIST_PROBE }
        val routine = routine(browser)
        val job = launch { routine.run(request(substack)) }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(routine.resultSoFar("stopped").outcome).isEqualTo(DraftOutcome.FAILED)
    }

    @Test
    fun `note images use paste first, and go before the body`() = runTest {
        val browser = noteBrowser().apply { imageRouteWorks = DraftScript.PASTE_IMAGE }
        val result = routine(browser).run(request(note, listOf(image)))

        assertThat(result.imagesExpected).isEqualTo(1)
        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(browser.scripts).doesNotContain(DraftScript.FILE_INPUT_IMAGE)
        assertThat(browser.scripts.indexOf(DraftScript.PASTE_IMAGE))
            .isLessThan(browser.scripts.indexOf(DraftScript.PASTE_HTML))
    }

    @Test
    fun `note images fall back to the file input`() = runTest {
        val browser = noteBrowser().apply { imageRouteWorks = DraftScript.FILE_INPUT_IMAGE }
        val result = routine(browser).run(request(note, listOf(image)))

        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
    }

    @Test
    fun `an image that lands nowhere is counted, and the article is still saved`() = runTest {
        val browser = noteBrowser()
        val result = routine(browser).run(request(note, listOf(image)))

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.imagesExpected).isEqualTo(1)
        assertThat(result.imagesPlaced).isEqualTo(0)
    }

    @Test
    fun `a failed tab close does not change the result`() = runTest {
        val browser = object : DraftBrowser by substackBrowser() {
            override suspend fun close(tabId: String) = throw IllegalStateException("no")
        }
        val result = routine(browser).run(request(substack))

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.tabClosed).isFalse()
    }

    @Test
    fun `the summary holds no article text`() = runTest {
        val result = routine(substackBrowser()).run(request(substack))
        val summary = result.toSummary()

        assertThat(summary).contains("\"outcome\":\"saved\"")
        assertThat(summary).contains(substackEditor)
        assertThat(summary).doesNotContain("A title")
        assertThat(summary).doesNotContain("Body text")
    }
}
