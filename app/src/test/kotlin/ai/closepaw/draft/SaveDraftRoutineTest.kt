package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private class Row(val hrefs: List<String>, val match: Boolean)

    /** A scripted Chrome. Each test overrides only what makes its case. */
    private class FakeBrowser(
        val listUrl: String,
        var editorUrl: String,
        val restored: List<DraftTab> = emptyList(),
    ) : DraftBrowser {
        val shown = mutableListOf<Unit>()
        val created = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val navigated = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val scripts = mutableListOf<DraftScript>()
        val tabsTouched = mutableSetOf<String>()
        var createTargetWorks = true
        var chromeAnswers = true
        var currentUrl: String? = null
        var listLoads = true
        var rows: List<Row> = emptyList()
        var editorHasContent = false
        var readBackOk = true
        var savedAnswers = true
        var saveLabelMatches = 1
        var pageImages = 0
        var imageRouteWorks: DraftScript? = null
        var hang: DraftScript? = null
        var movesTo: String? = null
        var movesBefore: DraftScript? = null
        var onWrite: () -> Unit = {}

        override suspend fun showChrome() {
            shown += Unit
        }

        override suspend fun createTab(url: String): String {
            created += url
            if (!createTargetWorks) throw IOException("not supported")
            currentUrl = if (listLoads) url else "https://substack.com/sign-in"
            return OURS
        }

        override suspend fun open(url: String) {
            opened += url
            currentUrl = url
        }

        override suspend fun tabs(): List<DraftTab> {
            if (!chromeAnswers) throw IOException("devtools socket missing")
            return restored + listOfNotNull(currentUrl?.let { DraftTab(OURS, it) })
        }

        override suspend fun navigate(tabId: String, url: String) {
            tabsTouched += tabId
            navigated += url
            currentUrl = editorUrl
        }

        override suspend fun close(tabId: String) {
            closed += tabId
        }

        override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
            if (script == hang) CompletableDeferred<Unit>().await()
            tabsTouched += tabId
            if (script == movesBefore) currentUrl = movesTo
            val expected = args["editorUrl"]?.jsonPrimitive?.content
            if (expected != null && expected != currentUrl) {
                return buildJsonObject {
                    put("ok", false)
                    put("moved", true)
                }
            }
            scripts += script
            return when (script) {
                DraftScript.LIST_PROBE -> buildJsonObject {
                    put("href", currentUrl)
                    put(
                        "rows",
                        buildJsonArray {
                            rows.forEach { row ->
                                add(
                                    buildJsonObject {
                                        put("match", row.match)
                                        put("hrefs", buildJsonArray { row.hrefs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                                    },
                                )
                            }
                        },
                    )
                }
                DraftScript.EDITOR_PROBE -> buildJsonObject {
                    put("href", currentUrl)
                    put("titleFound", true)
                    put("bodyFound", true)
                    put("titleEmpty", !editorHasContent)
                    put("bodyEmpty", !editorHasContent)
                }
                DraftScript.SET_TITLE, DraftScript.PASTE_HTML -> {
                    onWrite()
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.IMAGE_COUNT -> buildJsonObject { put("count", pageImages) }
                DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE -> {
                    if (script == imageRouteWorks) pageImages++
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.READ_BACK -> buildJsonObject {
                    put("titleOk", readBackOk)
                    put("bodyOk", readBackOk)
                }
                DraftScript.NOTE_SAVE_CLICK -> buildJsonObject {
                    put("ok", saveLabelMatches == 1)
                    put("matches", saveLabelMatches)
                    put("at", 12.5)
                }
                DraftScript.NOTE_SAVED_PROBE, DraftScript.SUBSTACK_SAVED_PROBE -> buildJsonObject {
                    put("ok", true)
                    put("saved", savedAnswers)
                    put("evidence", "outside=true api=$savedAnswers")
                }
            }
        }

        val writes get() = scripts.filter { it in WRITES }

        companion object {
            const val OURS = "ours"
            val WRITES = setOf(
                DraftScript.SET_TITLE, DraftScript.PASTE_HTML, DraftScript.PASTE_IMAGE,
                DraftScript.FILE_INPUT_IMAGE, DraftScript.NOTE_SAVE_CLICK,
            )
        }
    }

    private fun TestScope.routine(
        browser: DraftBrowser,
        site: DraftSite,
        images: List<DraftImage> = emptyList(),
        ownerHasChrome: () -> Boolean = { false },
    ) = SaveDraftRoutine(
        browser,
        request(site, images),
        ownerHasChrome,
        now = { testScheduler.currentTime },
        newMarker = { "closepaw-test" },
    )

    private fun substackBrowser() = FakeBrowser(substack.draftListUrl, substackEditor)

    private fun noteBrowser() = FakeBrowser(note.draftListUrl, noteEditor)

    // ── Happy paths ──

    @Test
    fun `substack saves by autosave and never clicks`() = runTest {
        val browser = substackBrowser()
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.url).isEqualTo(substackEditor)
        assertThat(result.titleOk && result.bodyOk && result.saved && result.wrote).isTrue()
        assertThat(result.tabClosed).isTrue()
        assertThat(browser.scripts).doesNotContain(DraftScript.NOTE_SAVE_CLICK)
        assertThat(browser.navigated).containsExactly(substack.newDraftUrl)
    }

    @Test
    fun `note clicks the save label exactly once`() = runTest {
        val browser = noteBrowser()
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.url).isEqualTo(noteEditor)
        assertThat(result.saveEvidence).isEqualTo("outside=true api=true")
        assertThat(browser.scripts.count { it == DraftScript.NOTE_SAVE_CLICK }).isEqualTo(1)
        assertThat(browser.scripts).doesNotContain(DraftScript.SUBSTACK_SAVED_PROBE)
    }

    // ── Item 1: an unconfirmed save ──

    @Test
    fun `an unconfirmed save is uncertain and nothing is repeated`() = runTest {
        val browser = noteBrowser().apply { savedAnswers = false }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.outcome.status).isEqualTo("uncertain")
        assertThat(result.reason).isEqualTo("save-unconfirmed")
        assertThat(result.saved).isFalse()
        assertThat(result.saveEvidence).isEqualTo("outside=true api=false")
        assertThat(result.url).isEqualTo(noteEditor)
        assertThat(browser.closed).isEmpty()
        assertThat(browser.scripts.count { it == DraftScript.NOTE_SAVE_CLICK }).isEqualTo(1)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(1)
    }

    @Test
    fun `no unique save control means no click`() = runTest {
        val browser = noteBrowser().apply { saveLabelMatches = 2 }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("save-control-not-unique")
    }

    @Test
    fun `a read-back mismatch is uncertain`() = runTest {
        val browser = substackBrowser().apply { readBackOk = false }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("read-back-mismatch")
    }

    // ── Item 4: same draft, and the owner's Chrome ──

    @Test
    fun `every write and the click pass the editor URL`() = runTest {
        val seen = mutableListOf<Pair<DraftScript, String?>>()
        val inner = noteBrowser()
        val browser = object : DraftBrowser by inner {
            override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
                seen += script to args["editorUrl"]?.jsonPrimitive?.content
                return inner.evaluate(tabId, script, args)
            }
        }
        routine(browser, note, listOf(image)).run()

        val guarded = FakeBrowser.WRITES + DraftScript.READ_BACK + DraftScript.NOTE_SAVED_PROBE
        seen.filter { it.first in guarded }.forEach { assertThat(it.second).isEqualTo(noteEditor) }
        assertThat(seen.map { it.first }).contains(DraftScript.NOTE_SAVE_CLICK)
    }

    @Test
    fun `a page that left the draft gets nothing more`() = runTest {
        DraftScript.values().filter { it in FakeBrowser.WRITES }.forEach { script ->
            val site = note
            val browser = noteBrowser().apply {
                imageRouteWorks = DraftScript.PASTE_IMAGE.takeIf { script != DraftScript.FILE_INPUT_IMAGE }
                movesTo = "https://note.com/somewhere"
                movesBefore = script
            }
            val result = routine(browser, site, listOf(image)).run()

            assertThat(result.reason).isEqualTo("editor-moved")
            assertThat(browser.scripts).doesNotContain(script)
        }
    }

    @Test
    fun `leaving the draft during the read-back is reported, not retried`() = runTest {
        val browser = substackBrowser().apply {
            movesTo = "https://example-pub.substack.com/publish/posts"
            movesBefore = DraftScript.READ_BACK
        }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("editor-moved")
    }

    @Test
    fun `the owner opening Chrome stops the next write`() = runTest {
        var ownerHere = false
        val browser = substackBrowser().apply { onWrite = { ownerHere = true } }
        val result = routine(browser, substack, ownerHasChrome = { ownerHere }).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("chrome-opened")
        assertThat(browser.writes).containsExactly(DraftScript.SET_TITLE)
    }

    @Test
    fun `the owner using Chrome before any write means nothing is written`() = runTest {
        val browser = substackBrowser()
        val result = routine(browser, substack, ownerHasChrome = { true }).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.FAILED)
        assertThat(result.reason).isEqualTo("chrome-opened")
        assertThat(result.wrote).isFalse()
        assertThat(browser.navigated).isEmpty()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `the owner opening Chrome during confirmation also ends the run`() = runTest {
        var ownerHere = false
        val browser = noteBrowser().apply {
            savedAnswers = false
            onWrite = {}
        }
        val routine = routine(browser, note, ownerHasChrome = { ownerHere })
        val job = launch {
            val result = routine.run()
            assertThat(result.reason).isEqualTo("chrome-opened")
        }
        advanceTimeBy(15_000L)
        ownerHere = true
        advanceUntilIdle()
        job.join()
    }

    // ── Item 6: only a tab this run created ──

    @Test
    fun `restored tabs are never touched, even when Chrome has just started`() = runTest {
        val restored = listOf(DraftTab("theirs-list", substack.draftListUrl), DraftTab("theirs-editor", substackEditor))
        val browser = FakeBrowser(substack.draftListUrl, substackEditor, restored)
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.tabsTouched).containsExactly(FakeBrowser.OURS)
        assertThat(browser.closed).containsExactly(FakeBrowser.OURS)
        assertThat(browser.opened).isEmpty()
    }

    @Test
    fun `Chrome is shown first and gets time to answer`() = runTest {
        val browser = substackBrowser().apply { chromeAnswers = false }
        val job = launch { routine(browser, substack).run() }
        advanceTimeBy(3_000L)
        runCurrent()
        assertThat(browser.shown).hasSize(1)
        assertThat(browser.created).isEmpty()
        browser.chromeAnswers = true
        advanceUntilIdle()
        job.join()
        assertThat(browser.created).containsExactly(substack.draftListUrl)
    }

    @Test
    fun `Chrome that never answers ends the run with nothing done`() = runTest {
        val browser = substackBrowser().apply { chromeAnswers = false }
        val result = routine(browser, substack).run()

        assertThat(result.reason).isEqualTo("chrome-not-reachable")
        assertThat(result.wrote).isFalse()
        assertThat(browser.created).isEmpty()
    }

    @Test
    fun `without createTarget the tab is proved by a random fragment`() = runTest {
        val restored = listOf(DraftTab("theirs", substack.draftListUrl))
        val browser = FakeBrowser(substack.draftListUrl, substackEditor, restored).apply { createTargetWorks = false }
        val result = routine(browser, substack).run()

        assertThat(browser.opened).containsExactly(substack.draftListUrl + "#closepaw-test")
        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.tabsTouched).containsExactly(FakeBrowser.OURS)
    }

    @Test
    fun `without proof of a tab nothing happens`() = runTest {
        val inner = FakeBrowser(substack.draftListUrl, substackEditor, listOf(DraftTab("theirs", substack.draftListUrl))).apply {
            createTargetWorks = false
        }
        val browser = object : DraftBrowser by inner {
            override suspend fun open(url: String) {
                inner.opened += url
            }
        }
        val result = routine(browser, substack).run()

        assertThat(result.reason).isEqualTo("tab-not-found")
        assertThat(inner.tabsTouched).isEmpty()
    }

    @Test
    fun `a signed-out run stops at the editor check, in its own tab`() = runTest {
        val browser = FakeBrowser(substack.draftListUrl, "https://substack.com/sign-in").apply { listLoads = false }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("editor-not-reached")
        assertThat(result.dedupe).isEqualTo("unknown")
        assertThat(result.url).isNull()
        assertThat(browser.created).hasSize(1)
        assertThat(browser.writes).isEmpty()
    }

    // ── Item 8: the draft list ──

    @Test
    fun `a same-title row with an editor link stops the run before any write`() = runTest {
        val link = "https://example-pub.substack.com/publish/post/77"
        val browser = substackBrowser().apply { rows = listOf(Row(listOf(link), true)) }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.ALREADY_EXISTS)
        assertThat(result.url).isEqualTo(link)
        assertThat(result.dedupe).isEqualTo("found")
        assertThat(result.wrote).isFalse()
        assertThat(result.tabClosed).isTrue()
        assertThat(browser.navigated).isEmpty()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `a same-title row with a bad link is not a duplicate`() = runTest {
        val browser = substackBrowser().apply {
            rows = listOf(Row(listOf("https://evil.test/publish/post/77", "https://other.substack.com/publish/post/1"), true))
        }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.dedupe).isEqualTo("unknown")
    }

    @Test
    fun `a rendered list without the title is only not-seen`() = runTest {
        val browser = substackBrowser().apply {
            rows = listOf(Row(listOf("https://example-pub.substack.com/publish/post/77"), false))
        }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.dedupe).isEqualTo("not-seen")
    }

    @Test
    fun `a list that never rendered is unknown, not absent`() = runTest {
        val browser = substackBrowser()
        val result = routine(browser, substack).run()

        assertThat(result.dedupe).isEqualTo("unknown")
    }

    @Test
    fun `a list on the wrong page is unknown even with matching rows`() = runTest {
        val browser = substackBrowser().apply {
            listLoads = false
            rows = listOf(Row(listOf("https://example-pub.substack.com/publish/post/77"), true))
        }
        val result = routine(browser, substack).run()

        assertThat(result.dedupe).isEqualTo("unknown")
        assertThat(result.outcome).isNotEqualTo(DraftOutcome.ALREADY_EXISTS)
    }

    // ── Editor preconditions ──

    @Test
    fun `a redirect away from the editor writes nothing`() = runTest {
        val browser = FakeBrowser(substack.draftListUrl, "https://substack.com/sign-in")
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("editor-not-reached")
        assertThat(result.wrote).isFalse()
        assertThat(result.url).isNull()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `an editor that is not empty is left alone`() = runTest {
        val browser = substackBrowser().apply { editorHasContent = true }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.FAILED)
        assertThat(result.reason).isEqualTo("editor-not-empty")
        assertThat(browser.writes).isEmpty()
        assertThat(browser.closed).isEmpty()
    }

    // ── Timeouts and stops ──

    @Test
    fun `a hang after writing is uncertain and nothing is retried`() = runTest {
        val browser = substackBrowser().apply { hang = DraftScript.READ_BACK }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("timeout")
        assertThat(result.wrote).isTrue()
        assertThat(browser.scripts.count { it == DraftScript.SET_TITLE }).isEqualTo(1)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(1)
        assertThat(browser.navigated).hasSize(1)
        assertThat(browser.closed).isEmpty()
    }

    @Test
    fun `a hang before writing is also uncertain, and says nothing was written`() = runTest {
        val browser = substackBrowser().apply { hang = DraftScript.EDITOR_PROBE }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("timeout")
        assertThat(result.wrote).isFalse()
        assertThat(browser.writes).isEmpty()
    }

    @Test
    fun `stopped mid-run reports what is known`() = runTest {
        val browser = substackBrowser().apply { hang = DraftScript.READ_BACK }
        val routine = routine(browser, substack)
        val job = launch { routine.run() }
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
        val routine = routine(browser, substack)
        val job = launch { routine.run() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertThat(routine.resultSoFar("stopped").outcome).isEqualTo(DraftOutcome.FAILED)
    }

    @Test
    fun `a failure before the run starts names the right platform`() = runTest {
        val onSubstack = routine(substackBrowser(), substack).resultSoFar("virtual-display-unavailable")
        val onNote = routine(noteBrowser(), note, listOf(image)).resultSoFar("virtual-display-unavailable")

        assertThat(onSubstack.platform).isEqualTo(DraftPlatform.SUBSTACK)
        assertThat(onSubstack.outcome).isEqualTo(DraftOutcome.FAILED)
        assertThat(onNote.platform).isEqualTo(DraftPlatform.NOTE)
        assertThat(onNote.imagesExpected).isEqualTo(1)
    }

    // ── Images ──

    @Test
    fun `note images use paste first, and go before the body`() = runTest {
        val browser = noteBrowser().apply { imageRouteWorks = DraftScript.PASTE_IMAGE }
        val result = routine(browser, note, listOf(image)).run()

        assertThat(result.imagesExpected).isEqualTo(1)
        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(browser.scripts).doesNotContain(DraftScript.FILE_INPUT_IMAGE)
        assertThat(browser.scripts.indexOf(DraftScript.PASTE_IMAGE))
            .isLessThan(browser.scripts.indexOf(DraftScript.PASTE_HTML))
    }

    @Test
    fun `note images fall back to the file input`() = runTest {
        val browser = noteBrowser().apply { imageRouteWorks = DraftScript.FILE_INPUT_IMAGE }
        val result = routine(browser, note, listOf(image)).run()

        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
    }

    @Test
    fun `an image that lands nowhere is counted, and the article is still saved`() = runTest {
        val browser = noteBrowser()
        val result = routine(browser, note, listOf(image)).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.imagesExpected).isEqualTo(1)
        assertThat(result.imagesPlaced).isEqualTo(0)
    }

    // ── Reporting ──

    @Test
    fun `a failed tab close does not change the result`() = runTest {
        val browser = object : DraftBrowser by substackBrowser() {
            override suspend fun close(tabId: String) = throw IllegalStateException("no")
        }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.tabClosed).isFalse()
    }

    @Test
    fun `the summary holds no article text`() = runTest {
        val summary = routine(substackBrowser(), substack).run().toSummary()

        assertThat(summary).contains("\"outcome\":\"saved\"")
        assertThat(summary).contains(substackEditor)
        assertThat(summary).doesNotContain("A title")
        assertThat(summary).doesNotContain("Body text")
    }
}
