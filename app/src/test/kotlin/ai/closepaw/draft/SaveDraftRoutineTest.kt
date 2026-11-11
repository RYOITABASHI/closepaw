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
        /** Scripts run, in order; null marks a click. */
        val scripts = mutableListOf<DraftScript?>()
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
        var uploadingPolls = 0
        var uploadingLeft = 0
        var pasteRefusals = 0
        var dialogWithPaste = false
        var dialogs = 0
        var bodyFilled = false
        var prepareRefusal: String? = null
        val prepared = mutableListOf<Unit>()
        val runTabs = mutableListOf<String>()
        var pasteIgnored = false
        var insertWorks = true
        var focusTargetOk = true
        val fronted = mutableListOf<String>()
        val clicks = mutableListOf<Pair<Double, Double>>()

        override suspend fun showChrome() {
            shown += Unit
        }

        override suspend fun prepareChrome(): String? {
            prepared += Unit
            return prepareRefusal
        }

        override suspend fun bringToFront(tabId: String) {
            fronted += tabId
        }

        override suspend fun click(tabId: String, x: Double, y: Double) {
            scripts += null
            clicks += x to y
        }

        override fun runTab(tabId: String) {
            runTabs += tabId
        }

        override fun dialogsAnswered(): Int = dialogs

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
                    put("bodyEmpty", !editorHasContent && !bodyFilled)
                }
                DraftScript.SET_TITLE -> {
                    onWrite()
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.PASTE_HTML -> {
                    onWrite()
                    when {
                        pasteRefusals > 0 -> {
                            // note's "uploading" alert, answered by the browser; the body stays empty.
                            pasteRefusals--
                            dialogs++
                        }
                        dialogWithPaste -> {
                            dialogs++
                            bodyFilled = true
                        }
                        pasteIgnored -> {}
                        else -> bodyFilled = true
                    }
                    buildJsonObject {
                        put("ok", true)
                        put("accepted", bodyFilled)
                        put("chars", if (bodyFilled) BODY_CHARS else 0)
                    }
                }
                DraftScript.INSERT_HTML -> {
                    onWrite()
                    if (bodyFilled || editorHasContent) {
                        buildJsonObject {
                            put("ok", false)
                            put("notEmpty", true)
                        }
                    } else {
                        if (insertWorks) bodyFilled = true
                        buildJsonObject {
                            put("ok", true)
                            put("accepted", insertWorks)
                            put("chars", if (bodyFilled) BODY_CHARS else 0)
                        }
                    }
                }
                DraftScript.FOCUS_TARGET -> buildJsonObject {
                    put("ok", focusTargetOk)
                    if (focusTargetOk) {
                        put("x", 18.0)
                        put("y", 110.0)
                    } else {
                        put("why", "no-block")
                    }
                }
                DraftScript.FOCUS_PROBE -> buildJsonObject {
                    put("href", currentUrl)
                    put("focused", clicks.isNotEmpty())
                    put("pageFocused", false)
                }
                DraftScript.IMAGE_COUNT -> buildJsonObject {
                    val uploading = uploadingLeft > 0
                    if (uploading) uploadingLeft--
                    // While uploading, the new image is only a local preview: not counted.
                    put("count", if (uploading) pageImages - 1 else pageImages)
                    put("local", if (uploading) 1 else 0)
                    put("uploading", uploading)
                }
                DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE -> {
                    if (script == imageRouteWorks) {
                        pageImages++
                        uploadingLeft = uploadingPolls
                    }
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.READ_BACK -> buildJsonObject {
                    put("titleOk", readBackOk)
                    put("bodyOk", readBackOk && bodyFilled)
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

        val writes get() = scripts.filterNotNull().filter { it in WRITES }

        companion object {
            const val OURS = "ours"
            const val BODY_CHARS = 14
            val WRITES = setOf(
                DraftScript.SET_TITLE, DraftScript.PASTE_HTML, DraftScript.PASTE_IMAGE,
                DraftScript.FILE_INPUT_IMAGE, DraftScript.NOTE_SAVE_CLICK,
            )
        }
    }

    private val logged = mutableListOf<String>()
    private val info = mutableListOf<String>()

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
        logFailure = { message, _ -> logged += message },
        logInfo = { message -> info += message },
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

    // ── Image upload completion ──

    @Test
    fun `an image counts only once it is stored and no upload is running`() {
        val state = { count: Int, uploading: Boolean ->
            buildJsonObject {
                put("count", count)
                put("uploading", uploading)
            }
        }
        assertThat(draftImageLanded(0, state(1, false))).isTrue()
        assertThat(draftImageLanded(0, state(1, true))).isFalse()
        assertThat(draftImageLanded(0, state(0, false))).isFalse()
        assertThat(draftImageLanded(1, state(1, false))).isFalse()
        assertThat(draftImageLanded(0, buildJsonObject { put("count", 1) })).isFalse()
        assertThat(draftImageLanded(0, buildJsonObject { put("uploading", false) })).isFalse()
    }

    @Test
    fun `a local preview is not a placed image, the upload is awaited`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            uploadingPolls = 5
        }
        val result = routine(browser, note, listOf(image)).run()

        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.scripts.count { it == DraftScript.IMAGE_COUNT }).isAtLeast(6)
        assertThat(browser.scripts).doesNotContain(DraftScript.FILE_INPUT_IMAGE)
        assertThat(browser.scripts.lastIndexOf(DraftScript.IMAGE_COUNT))
            .isLessThan(browser.scripts.indexOf(DraftScript.PASTE_HTML))
    }

    @Test
    fun `an upload still running at the limit is not retried by the other route`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            uploadingPolls = 1_000
        }
        val result = routine(browser, note, listOf(image)).run()

        assertThat(result.imagesPlaced).isEqualTo(0)
        assertThat(browser.scripts).doesNotContain(DraftScript.FILE_INPUT_IMAGE)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_IMAGE }).isEqualTo(1)
    }

    // ── JavaScript dialogs ──

    @Test
    fun `only an alert is accepted, every other dialog is dismissed`() {
        assertThat(draftDialogAccept("alert")).isTrue()
        assertThat(draftDialogAccept("confirm")).isFalse()
        assertThat(draftDialogAccept("prompt")).isFalse()
        assertThat(draftDialogAccept("beforeunload")).isFalse()
        assertThat(draftDialogAccept("unknown")).isFalse()
    }

    @Test
    fun `a paste refused with a dialog is pasted once more into the empty body`() = runTest {
        val browser = noteBrowser().apply { pasteRefusals = 1 }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(2)
    }

    @Test
    fun `a paste is retried only once`() = runTest {
        val browser = noteBrowser().apply {
            pasteRefusals = 2
            readBackOk = false
        }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(2)
        // After the second refused paste, the editing command gets one go at the empty body.
        assertThat(browser.scripts.count { it == DraftScript.INSERT_HTML }).isEqualTo(1)
    }

    @Test
    fun `a dialog with a filled body is not a reason to paste again`() = runTest {
        val browser = noteBrowser().apply { dialogWithPaste = true }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(1)
    }

    @Test
    fun `a paste that fills the body is checked once and not repeated`() = runTest {
        val browser = noteBrowser()
        routine(browser, note).run()

        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(1)
        assertThat(browser.scripts.count { it == DraftScript.EDITOR_PROBE }).isEqualTo(2)
        assertThat(browser.scripts).doesNotContain(DraftScript.INSERT_HTML)
    }

    @Test
    fun `a paste the editor ignores goes in once through the editing command`() = runTest {
        val browser = noteBrowser().apply { pasteIgnored = true }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_HTML }).isEqualTo(1)
        assertThat(browser.scripts.count { it == DraftScript.INSERT_HTML }).isEqualTo(1)
        val probeBefore = browser.scripts.lastIndexOf(DraftScript.EDITOR_PROBE)
        assertThat(probeBefore).isLessThan(browser.scripts.indexOf(DraftScript.INSERT_HTML))
    }

    @Test
    fun `when nothing takes the body the run says so and inserts only once`() = runTest {
        val browser = noteBrowser().apply {
            pasteIgnored = true
            insertWorks = false
        }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("read-back-mismatch")
        assertThat(browser.scripts.count { it == DraftScript.INSERT_HTML }).isEqualTo(1)
        assertThat(info).contains("body method=insert ok=true accepted=false bodyChars=0 dialogs=0")
    }

    // ── Focus before the body ──

    @Test
    fun `the body is focused for real before the paste`() = runTest {
        val browser = noteBrowser()
        routine(browser, note).run()

        val target = browser.scripts.indexOf(DraftScript.FOCUS_TARGET)
        val click = browser.scripts.indexOf(null)
        val probe = browser.scripts.indexOf(DraftScript.FOCUS_PROBE)
        val paste = browser.scripts.indexOf(DraftScript.PASTE_HTML)
        assertThat(listOf(target, click, probe, paste)).isInOrder()
        assertThat(browser.clicks).containsExactly(18.0 to 110.0)
        // Chrome is on the run's display, so the run's tab is shown there.
        assertThat(browser.fronted).containsExactly(FakeBrowser.OURS)
        assertThat(info).contains("focus front=true clicked=true target=ok focused=true pageFocused=false")
    }

    @Test
    fun `no safe point means no click, and the paste still goes ahead`() = runTest {
        val browser = noteBrowser().apply { focusTargetOk = false }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.clicks).isEmpty()
        assertThat(info).contains("focus front=true clicked=false target=no-block focused=false pageFocused=false")
    }

    @Test
    fun `a click that fails is logged and the paste goes ahead`() = runTest {
        val inner = noteBrowser()
        val browser = object : DraftBrowser by inner {
            override suspend fun click(tabId: String, x: Double, y: Double) = throw IOException("x")
        }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(info).contains("focus front=true clicked=false failed=IOException")
    }

    @Test
    fun `the step log holds outcomes and lengths, never article text`() = runTest {
        val browser = noteBrowser().apply { imageRouteWorks = DraftScript.PASTE_IMAGE }
        routine(browser, note, listOf(image)).run()

        assertThat(info).containsAtLeast(
            "tab ready",
            "editor reached",
            "title set",
            "images placed=1/1",
            "body method=paste ok=true accepted=true bodyChars=14 dialogs=0",
        ).inOrder()
        info.forEach { line ->
            assertThat(line).doesNotContain("A title")
            assertThat(line).doesNotContain("Body text")
        }
    }

    // ── Chrome kept off the owner's screen ──

    @Test
    fun `Chrome is prepared before it is shown, and the run's tab is handed over`() = runTest {
        val browser = substackBrowser()
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.prepared).hasSize(1)
        assertThat(browser.shown).hasSize(1)
        assertThat(browser.runTabs).containsExactly(FakeBrowser.OURS)
    }

    @Test
    fun `a refused preparation starts nothing`() = runTest {
        val browser = substackBrowser().apply { prepareRefusal = DraftChromeStage.CHROME_IN_USE }
        val result = routine(browser, substack).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.FAILED)
        assertThat(result.reason).isEqualTo("chrome-in-use")
        assertThat(result.wrote).isFalse()
        assertThat(browser.shown).isEmpty()
        assertThat(browser.created).isEmpty()
        assertThat(browser.opened).isEmpty()
        assertThat(info).contains("chrome not prepared: chrome-in-use")
    }

    // ── Failure reasons ──

    @Test
    fun `an error reason names the class and the step, not the message`() {
        assertThat(draftErrorReason(IllegalStateException("page text"), STEP_PASTE))
            .isEqualTo("error-IllegalStateException@paste")
    }

    @Test
    fun `a thrown error reports and logs the step it happened in, without article text`() = runTest {
        val inner = substackBrowser()
        val browser = object : DraftBrowser by inner {
            override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
                if (script == DraftScript.SET_TITLE) throw IllegalStateException("A title")
                return inner.evaluate(tabId, script, args)
            }
        }
        val result = routine(browser, substack).run()

        assertThat(result.reason).isEqualTo("error-IllegalStateException@title")
        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(logged).containsExactly("draft routine failed at step=title: java.lang.IllegalStateException")
    }

    @Test
    fun `an error while saving names the save step`() = runTest {
        val inner = noteBrowser()
        val browser = object : DraftBrowser by inner {
            override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
                if (script == DraftScript.NOTE_SAVE_CLICK) throw IOException("x")
                return inner.evaluate(tabId, script, args)
            }
        }
        assertThat(routine(browser, note).run().reason).isEqualTo("error-IOException@save")
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
