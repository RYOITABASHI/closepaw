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

    private fun request(site: DraftSite, images: List<DraftImage> = emptyList(), html: String = "<p>Body text here</p>") =
        SaveDraftRequest(site, "A title", html, listOf("Body text here"), images)

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
        var pasteKeyIgnored = false
        var pageClipboardWorks = true
        var androidClipboardWorks = true
        var clip = false
        var pasteKeys = 0
        var androidClips = 0
        var clipboardClears = 0
        var flattenedBody = false
        var insertWorks = true
        val anchorsInEditor = mutableMapOf<String, Int>()
        var selectedMarker: String? = null
        var caretEmpty = false
        var backspaceWorks = true
        var imageHereWorks = true
        var backspaces = 0
        var enters = 0
        var leaveCaretInFigure = false
        var caretInFigure = false
        var caretInEmptyP = false
        var trailingParagraph = true
        var enterMakesParagraph = true
        var figureSelected = false
        var pasteFlattens = false
        var mergeOnBackspace = false
        var mergeAtEnd = true
        var mergedCaret = false
        var titleFocused = false
        var bodyFocused = false
        var focusBodyWorks = true
        var pasteGoesToTitle = 0
        var titleClobbered = false
        var insertKeepsStructure = false
        var deletes = 0
        var coverInputOk = true
        var cropDialogNeeded = false
        var cropDialog = false
        var coverCount = 0
        var coverControl = false
        var chooserOnControl = false
        var menuOnControl = false
        var menuShown = false
        var chooserPending: Long? = null
        var chooserFileWorks = true
        val intercepts = mutableListOf<Boolean>()
        var coveredUntilHover = false
        val hovers = mutableListOf<Pair<Double, Double>>()
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
            when {
                cropDialog -> {
                    cropDialog = false
                    coverCount++
                }
                x == CONTROL_X -> {
                    if (chooserOnControl) chooserPending = CHOOSER_NODE
                    if (menuOnControl) menuShown = true
                }
                x == MENU_X && menuShown -> {
                    menuShown = false
                    chooserPending = CHOOSER_NODE
                }
                else -> titleFocused = false
            }
        }

        override suspend fun hover(tabId: String, x: Double, y: Double) {
            hovers += x to y
            coveredUntilHover = false
        }

        override suspend fun interceptFileChooser(tabId: String, enabled: Boolean) {
            intercepts += enabled
        }

        override suspend fun takeFileChooser(tabId: String): Long? = chooserPending.also { chooserPending = null }

        override suspend fun fileToChooser(tabId: String, backendNodeId: Long, args: JsonObject): JsonObject {
            scripts += DraftScript.FILE_TO_INPUT
            if (chooserFileWorks && backendNodeId == CHOOSER_NODE) {
                if (cropDialogNeeded) cropDialog = true else coverCount++
            }
            return buildJsonObject { put("ok", chooserFileWorks) }
        }

        override fun runTab(tabId: String) {
            runTabs += tabId
        }

        override suspend fun setClipboard(html: String): Boolean {
            androidClips++
            if (androidClipboardWorks) clip = true
            return androidClipboardWorks
        }

        override suspend fun pressKey(tabId: String, key: DraftKey) {
            if (key == DraftKey.DELETE) {
                deletes++
                caretEmpty = false
                return
            }
            if (key == DraftKey.ENTER) {
                enters++
                if (mergedCaret) {
                    mergedCaret = false
                    caretEmpty = true
                }
                if (enterMakesParagraph && figureSelected) trailingParagraph = true
                figureSelected = false
                return
            }
            backspaces++
            if (!backspaceWorks) return
            val marker = selectedMarker
            if (marker != null) {
                anchorsInEditor[marker] = (anchorsInEditor[marker] ?: 1) - 1
                selectedMarker = null
                if (mergeOnBackspace) mergedCaret = true else caretEmpty = true
            } else if (caretEmpty) {
                caretEmpty = false
            }
        }

        override suspend fun clearClipboard() {
            clipboardClears++
            clip = false
        }

        override suspend fun pasteKey(tabId: String) {
            pasteKeys++
            onWrite()
            if (!clip) return
            if (pasteGoesToTitle > 0) {
                pasteGoesToTitle--
                titleClobbered = true
                return
            }
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
                pasteKeyIgnored -> {}
                pasteFlattens -> {
                    bodyFilled = true
                    flattenedBody = true
                }
                else -> bodyFilled = true
            }
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
                    titleClobbered = false
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.FOCUS_BODY -> buildJsonObject {
                    if (focusBodyWorks) {
                        bodyFocused = true
                        titleFocused = false
                    }
                    put("ok", focusBodyWorks)
                }
                DraftScript.COVER_FILE -> buildJsonObject {
                    put("ok", coverInputOk)
                    put("total", 2)
                    put("candidates", if (coverInputOk) 1 else 0)
                    if (coverInputOk) {
                        if (cropDialogNeeded) cropDialog = true else coverCount++
                    }
                }
                DraftScript.COVER_CONTROL -> buildJsonObject {
                    put("ok", coverControl && !coveredUntilHover)
                    put("count", if (coverControl) 1 else 0)
                    if (coverControl) {
                        put("x", CONTROL_X)
                        put("y", 60.0)
                        if (coveredUntilHover) {
                            put("why", "covered")
                            put("coveredBy", "div sticky header")
                        }
                    }
                }
                DraftScript.COVER_MENU_ITEM -> buildJsonObject {
                    put("ok", menuShown)
                    put("count", if (menuShown) 1 else 0)
                    if (menuShown) {
                        put("x", MENU_X)
                        put("y", 80.0)
                    }
                }
                DraftScript.FILE_TO_INPUT -> buildJsonObject { put("ok", false) }
                DraftScript.COVER_DIAG -> buildJsonObject {
                    put("titleFound", true)
                    put("fileInputs", 0)
                    put("shadowFileInputs", 0)
                    put("frames", 1)
                    put("frameFileInputs", 0)
                    put("unreadableFrames", 1)
                    put(
                        "candidates",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("tag", "button")
                                    put("role", "")
                                    put("aria", "画像を設定")
                                    put("title", "")
                                    put("data", "data-testid")
                                    put("cls", "eyecatch")
                                    put("text", "")
                                    put("w", 40)
                                    put("h", 40)
                                    put("icon", "svg")
                                },
                            )
                        },
                    )
                }
                DraftScript.COVER_STATE -> buildJsonObject {
                    put("count", coverCount)
                    put("dialog", cropDialog)
                    put("pressable", cropDialog)
                    if (cropDialog) {
                        put("x", 140.0)
                        put("y", 220.0)
                    }
                }
                DraftScript.CLIPBOARD_WRITE -> buildJsonObject {
                    if (pageClipboardWorks) clip = true
                    put("ok", pageClipboardWorks)
                    if (!pageClipboardWorks) put("why", "NotAllowedError")
                }
                DraftScript.ANCHOR_SELECT -> buildJsonObject {
                    val marker = args["marker"]!!.jsonPrimitive.content
                    val count = anchorsInEditor[marker] ?: 0
                    val any = args["any"]?.jsonPrimitive?.content == "true"
                    val ok = count == 1 || (count > 1 && any)
                    if (ok) selectedMarker = marker
                    put("ok", ok)
                    put("count", count)
                }
                DraftScript.ANCHOR_CHECK -> buildJsonObject {
                    val marker = args["marker"]!!.jsonPrimitive.content
                    put("href", currentUrl)
                    put("count", anchorsInEditor[marker] ?: 0)
                    put("caretEmpty", caretEmpty)
                    put("caretAtEnd", if (mergedCaret) mergeAtEnd else true)
                    put("prevPlain", true)
                    put("afterPlain", true)
                }
                DraftScript.PASTE_IMAGE_HERE -> {
                    if (imageHereWorks && caretEmpty) {
                        pageImages++
                        caretEmpty = false
                    }
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.BODY_SHAPE -> buildJsonObject {
                    put("found", true)
                    put("chars", if (bodyFilled) BODY_CHARS else 0)
                    put("p", if (!bodyFilled) 0 else if (flattenedBody) 1 else 3)
                    put("h", if (bodyFilled && !flattenedBody) 1 else 0)
                    put("li", 0)
                    if (args.containsKey("title")) put("titleSame", !titleClobbered)
                }
                DraftScript.INSERT_HTML -> {
                    onWrite()
                    if (bodyFilled || editorHasContent) {
                        buildJsonObject {
                            put("ok", false)
                            put("notEmpty", true)
                        }
                    } else {
                        if (insertWorks) {
                            bodyFilled = true
                            flattenedBody = !insertKeepsStructure
                        }
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
                        put("on", "block")
                    } else {
                        put("why", "no-block")
                    }
                }
                DraftScript.FOCUS_PROBE -> buildJsonObject {
                    put("href", currentUrl)
                    val inBody = (clicks.isNotEmpty() || bodyFocused) && !titleFocused
                    put("focused", inBody)
                    put("pageFocused", false)
                    put(
                        "where",
                        when {
                            caretInFigure -> "figure"
                            titleFocused -> "title"
                            inBody -> "body"
                            else -> "none"
                        },
                    )
                    put("inFigure", caretInFigure)
                    put("caretEmptyP", caretInEmptyP || caretEmpty)
                }
                DraftScript.CARET_AFTER_IMAGES -> buildJsonObject {
                    if (args["mode"]?.jsonPrimitive?.content == "select-figure") {
                        figureSelected = true
                        put("ok", true)
                        put("kind", "figure")
                    } else if (trailingParagraph) {
                        caretInFigure = false
                        caretInEmptyP = true
                        put("ok", true)
                        put("kind", "empty-p")
                    } else {
                        put("ok", false)
                        put("kind", "figure")
                    }
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
                        caretInFigure = leaveCaretInFigure
                        caretInEmptyP = false
                    }
                    buildJsonObject { put("ok", true) }
                }
                DraftScript.READ_BACK -> buildJsonObject {
                    put("titleOk", readBackOk && !titleClobbered)
                    val markersLeft = anchorsInEditor.values.sum() > 0
                    put("bodyOk", readBackOk && bodyFilled && !flattenedBody && !markersLeft)
                    put("markersLeft", markersLeft)
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
            const val CONTROL_X = 50.0
            const val MENU_X = 70.0
            const val CHOOSER_NODE = 77L
            val WRITES = setOf(
                DraftScript.SET_TITLE, DraftScript.CLIPBOARD_WRITE, DraftScript.PASTE_IMAGE,
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
        html: String = "<p>Body text here</p>",
    ) = SaveDraftRoutine(
        browser,
        request(site, images, html),
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
        assertThat(browser.pasteKeys).isEqualTo(1)
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
        assertThat(browser.pasteKeys).isEqualTo(1)
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
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
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
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
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
        assertThat(browser.pasteKeys).isEqualTo(2)
    }

    @Test
    fun `a paste is retried only once`() = runTest {
        val browser = noteBrowser().apply {
            pasteRefusals = 2
            readBackOk = false
        }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(browser.pasteKeys).isEqualTo(2)
        // After the second refused paste, the editing command gets one go at the empty body.
        assertThat(browser.scripts.count { it == DraftScript.INSERT_HTML }).isEqualTo(1)
    }

    @Test
    fun `a dialog with a filled body is not a reason to paste again`() = runTest {
        val browser = noteBrowser().apply { dialogWithPaste = true }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.pasteKeys).isEqualTo(1)
    }

    @Test
    fun `a paste that fills the body is checked once and not repeated`() = runTest {
        val browser = noteBrowser()
        routine(browser, note).run()

        assertThat(browser.pasteKeys).isEqualTo(1)
        assertThat(browser.scripts.count { it == DraftScript.EDITOR_PROBE }).isEqualTo(2)
        assertThat(browser.scripts).doesNotContain(DraftScript.INSERT_HTML)
    }

    @Test
    fun `a body that only the insert command takes is reported flattened, never saved`() = runTest {
        val browser = noteBrowser().apply { pasteKeyIgnored = true }
        val result = routine(browser, note, html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("body-flattened")
        assertThat(result.bodyOk).isFalse()
        // Two trusted pastes, each into an empty body, then the last resort once.
        assertThat(browser.pasteKeys).isEqualTo(2)
        assertThat(browser.scripts.count { it == DraftScript.INSERT_HTML }).isEqualTo(1)
        assertThat(browser.scripts.lastIndexOf(DraftScript.CLIPBOARD_WRITE))
            .isLessThan(browser.scripts.indexOf(DraftScript.INSERT_HTML))
        assertThat(info).contains("body method=insert ok=true accepted=true chars=14 p=1 h=0 li=0 dialogs=0")
    }

    @Test
    fun `when nothing takes the body the run says so and inserts only once`() = runTest {
        val browser = noteBrowser().apply {
            pasteKeyIgnored = true
            insertWorks = false
        }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("read-back-mismatch")
        assertThat(browser.scripts.count { it == DraftScript.INSERT_HTML }).isEqualTo(1)
        assertThat(info).contains("body method=insert ok=true accepted=false chars=0 p=0 h=0 li=0 dialogs=0")
    }

    // ── Focus before the body ──

    @Test
    fun `the body is focused for real before the paste`() = runTest {
        val browser = noteBrowser()
        routine(browser, note).run()

        val target = browser.scripts.indexOf(DraftScript.FOCUS_TARGET)
        val click = browser.scripts.indexOf(null)
        val probe = browser.scripts.indexOf(DraftScript.FOCUS_PROBE)
        val paste = browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE)
        assertThat(listOf(target, click, probe, paste)).isInOrder()
        assertThat(browser.clicks).containsExactly(18.0 to 110.0)
        // Chrome is on the run's display, so the run's tab is shown there.
        assertThat(browser.fronted).containsExactly(FakeBrowser.OURS)
        assertThat(info).contains("focus front=true clicked=true target=block focused=true pageFocused=false")
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
            "body method=paste clip=page chars=14 p=3 h=1 li=0 dialogs=0",
        ).inOrder()
        info.forEach { line ->
            assertThat(line).doesNotContain("A title")
            assertThat(line).doesNotContain("Body text")
        }
    }

    // ── The body, and only the body ──

    @Test
    fun `an empty body that cannot be clicked gets the caret by focus and a range`() = runTest {
        val browser = noteBrowser().apply {
            titleFocused = true
            focusTargetOk = false
        }
        val result = routine(browser, note, html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(info).contains("caret where=title -> body focusBody=true focused=true")
        assertThat(browser.scripts.indexOf(DraftScript.FOCUS_BODY))
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
    }

    @Test
    fun `a caret that cannot reach the body writes nothing`() = runTest {
        val browser = noteBrowser().apply {
            titleFocused = true
            focusTargetOk = false
            focusBodyWorks = false
        }
        val result = routine(browser, note, html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("focus-not-body")
        assertThat(browser.pasteKeys).isEqualTo(0)
        assertThat(browser.scripts).doesNotContain(DraftScript.CLIPBOARD_WRITE)
        assertThat(browser.scripts).doesNotContain(DraftScript.INSERT_HTML)
        assertThat(info).contains("body method=paste refused=focus-not-body")
    }

    @Test
    fun `a paste that lands in the title is undone and reported`() = runTest {
        val browser = noteBrowser().apply { pasteGoesToTitle = 1 }
        val result = routine(browser, note, html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("title-overwritten")
        assertThat(result.titleOk).isTrue()
        assertThat(browser.titleClobbered).isFalse()
        assertThat(browser.scripts.count { it == DraftScript.SET_TITLE }).isEqualTo(2)
        assertThat(info).contains("title overwritten by method=paste; restored=true")
    }

    @Test
    fun `an insert that keeps the structure in the body is a good body`() = runTest {
        val browser = noteBrowser().apply {
            pasteKeyIgnored = true
            insertKeepsStructure = true
        }
        val result = routine(browser, note, html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.bodyOk).isTrue()
        assertThat(browser.scripts.count { it == DraftScript.INSERT_HTML }).isEqualTo(1)
    }

    // ── note's cover image ──

    private val coverHtml = "<p>{{closepaw-cover-1}}</p><h2>Head</h2><p>Body text here</p>"

    @Test
    fun `the cover marker makes the first image note's cover, before the body`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            cropDialogNeeded = true
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.cover).isEqualTo("placed")
        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(result.toSummary()).contains("\"cover\":\"placed\"")
        assertThat(browser.anchorsInEditor[COVER_MARKER]).isEqualTo(0)
        assertThat(browser.scripts).doesNotContain(DraftScript.PASTE_IMAGE)
        assertThat(browser.scripts).doesNotContain(DraftScript.PASTE_IMAGE_HERE)
        // The cover while the page is at the top; its marker goes after the body.
        assertThat(browser.scripts.indexOf(DraftScript.COVER_FILE))
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
        assertThat(browser.scripts.lastIndexOf(DraftScript.ANCHOR_SELECT))
            .isGreaterThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
        assertThat(info).contains("cover: crop dialog pressed")
        assertThat(info).contains("cover: placed")
    }

    @Test
    fun `a cover that cannot be set goes into the body at its marker`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.cover).isEqualTo("failed-body")
        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(result.anchors).isEmpty()
        assertThat(result.toSummary()).contains("\"cover\":\"failed-body\"")
        assertThat(browser.anchorsInEditor[COVER_MARKER]).isEqualTo(0)
        assertThat(browser.scripts.count { it == DraftScript.PASTE_IMAGE_HERE }).isEqualTo(1)
        // The cover is tried first, the body only after.
        assertThat(browser.scripts.indexOf(DraftScript.COVER_FILE))
            .isLessThan(browser.scripts.indexOf(DraftScript.ANCHOR_SELECT))
        assertThat(info).contains("cover: failed-body")
    }

    @Test
    fun `when neither the cover nor the body takes it, the marker still goes`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
            imageHereWorks = false
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.cover).isEqualTo("failed")
        assertThat(result.imagesPlaced).isEqualTo(0)
        assertThat(browser.anchorsInEditor[COVER_MARKER]).isEqualTo(0)
    }

    @Test
    fun `note's cover control is pressed and the chooser's input gets the file`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
            coverControl = true
            chooserOnControl = true
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.cover).isEqualTo("placed")
        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(browser.intercepts).containsExactly(true, false).inOrder()
        assertThat(browser.clicks.map { it.first }).contains(FakeBrowser.CONTROL_X)
        assertThat(info).containsAtLeast(
            "cover: input ok=false inputs=2 candidates=0",
            "cover: control found=1",
            "cover: control pressed",
            "cover: chooser file ok=true",
            "cover: placed",
        ).inOrder()
    }

    @Test
    fun `a covered cover control is hovered, looked at again, and pressed`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
            coverControl = true
            coveredUntilHover = true
            chooserOnControl = true
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.cover).isEqualTo("placed")
        assertThat(browser.hovers).containsExactly(FakeBrowser.CONTROL_X to 60.0)
        assertThat(info).containsAtLeast(
            "cover: control found=1 why=covered by=div sticky header",
            "cover: control found=1 (after hover)",
            "cover: control pressed",
        ).inOrder()
        // The body still gets the caret back after the cover.
        assertThat(browser.scripts.indexOf(DraftScript.COVER_CONTROL))
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
    }

    @Test
    fun `a control still covered after the hover is not pressed`() = runTest {
        val fake = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
            coverControl = true
            coveredUntilHover = true
        }
        val stuck = object : DraftBrowser by fake {
            override suspend fun hover(tabId: String, x: Double, y: Double) {
                fake.hovers += x to y
            }
        }
        val result = routine(stuck, note, listOf(image), html = coverHtml).run()

        assertThat(result.cover).isEqualTo("failed-body")
        assertThat(fake.hovers).hasSize(1)
        assertThat(fake.clicks.map { it.first }).doesNotContain(FakeBrowser.CONTROL_X)
    }

    @Test
    fun `a cover menu's upload item is pressed before the chooser`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
            coverControl = true
            menuOnControl = true
            cropDialogNeeded = true
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.cover).isEqualTo("placed")
        assertThat(browser.clicks.map { it.first })
            .containsAtLeast(FakeBrowser.CONTROL_X, FakeBrowser.MENU_X, 140.0).inOrder()
        assertThat(info).contains("cover: menu item pressed")
        assertThat(info).contains("cover: crop dialog pressed")
    }

    @Test
    fun `no cover control means the body fallback, and the chooser is released`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.cover).isEqualTo("failed-body")
        assertThat(browser.intercepts).containsExactly(true, false).inOrder()
        assertThat(browser.scripts).doesNotContain(DraftScript.FILE_TO_INPUT)
        assertThat(info).contains("cover: control found=0")
        assertThat(info).contains(
            "cover: diag titleFound=true fileInputs=0 shadowFileInputs=0 frames=1 frameFileInputs=0 unreadableFrames=1",
        )
        assertThat(info).contains(
            "cover: diag[0] tag=button role= aria=画像を設定 title= data=data-testid class=eyecatch text= box=40x40 icon=svg",
        )
        info.forEach { line ->
            assertThat(line).doesNotContain("A title")
            assertThat(line).doesNotContain("Body text")
        }
    }

    @Test
    fun `a matched cover control does not run the diagnostic`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
            coverControl = true
            chooserOnControl = true
        }
        routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(browser.scripts).doesNotContain(DraftScript.COVER_DIAG)
    }

    @Test
    fun `a pressed control that never asks for a file falls back to the body`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[COVER_MARKER] = 1
            coverInputOk = false
            coverControl = true
        }
        val result = routine(browser, note, listOf(image), html = coverHtml).run()

        assertThat(result.cover).isEqualTo("failed-body")
        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.intercepts.last()).isFalse()
    }

    @Test
    fun `the call on a node runs the fixed script with the image as JSON`() {
        val params = draftCallParams(DraftScript.FILE_TO_INPUT, "obj-1", buildJsonObject { put("name", "a.jpg") })
        assertThat(params["functionDeclaration"]!!.jsonPrimitive.content).isEqualTo(DraftScript.FILE_TO_INPUT.source.trim())
        assertThat(params["objectId"]!!.jsonPrimitive.content).isEqualTo("obj-1")
        assertThat(params.toString()).contains("\"arguments\":[{\"value\":{\"name\":\"a.jpg\"}}]")
    }

    @Test
    fun `elsewhere the cover marker anchors the first image`() = runTest {
        val browser = substackBrowser().apply { anchorsInEditor[COVER_MARKER] = 1 }
        val result = routine(browser, substack, listOf(image), html = coverHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.cover).isEqualTo("absent")
        assertThat(result.anchors).containsExactly("1:placed")
        assertThat(browser.scripts).doesNotContain(DraftScript.COVER_FILE)
    }

    @Test
    fun `the cover marker is found only as its own paragraph`() {
        assertThat(draftHasCoverMarker(coverHtml)).isTrue()
        assertThat(draftHasCoverMarker("<p>see {{closepaw-cover-1}}</p>")).isFalse()
        assertThat(draftHasCoverMarker("<p>Body</p>")).isFalse()
    }

    // ── The caret after leading images ──

    private val threeBlocks = "<h2>Head</h2><p>Body text here</p><p>More</p>"

    @Test
    fun `after the header the caret goes into the empty paragraph after it`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            leaveCaretInFigure = true
        }
        val result = routine(browser, note, listOf(image), html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.enters).isEqualTo(0)
        assertThat(info).contains("caret after images: empty-p")
        assertThat(browser.scripts.indexOf(DraftScript.CARET_AFTER_IMAGES))
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
    }

    @Test
    fun `with no paragraph after the header, Enter on the selected image makes one`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            leaveCaretInFigure = true
            trailingParagraph = false
        }
        val result = routine(browser, note, listOf(image), html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.enters).isEqualTo(1)
        assertThat(info).contains("caret after images: new-p")
    }

    @Test
    fun `a caret that stays in the figure is never pasted into`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            leaveCaretInFigure = true
            trailingParagraph = false
            enterMakesParagraph = false
        }
        val result = routine(browser, note, listOf(image), html = threeBlocks).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("focus-in-figure")
        assertThat(result.url).isEqualTo(noteEditor)
        assertThat(browser.pasteKeys).isEqualTo(0)
        assertThat(browser.scripts).doesNotContain(DraftScript.CLIPBOARD_WRITE)
        assertThat(browser.scripts).doesNotContain(DraftScript.INSERT_HTML)
        assertThat(info).contains("body method=paste refused=focus-in-figure")
        assertThat(result.toSummary()).contains(noteEditor)
    }

    @Test
    fun `without leading images the caret is not moved`() = runTest {
        val browser = noteBrowser()
        routine(browser, note, html = threeBlocks).run()

        assertThat(browser.scripts).doesNotContain(DraftScript.CARET_AFTER_IMAGES)
        assertThat(browser.enters).isEqualTo(0)
    }

    @Test
    fun `a paste that arrives as one paragraph is flattened and not inserted again`() = runTest {
        val browser = noteBrowser().apply { pasteFlattens = true }
        val result = routine(browser, note, html = threeBlocks).run()

        assertThat(result.reason).isEqualTo("body-flattened")
        assertThat(result.bodyOk).isFalse()
        assertThat(browser.pasteKeys).isEqualTo(1)
        assertThat(browser.scripts).doesNotContain(DraftScript.INSERT_HTML)
        assertThat(info).contains("body flattened: blocks=1 of 3")
    }

    @Test
    fun `a short source in one paragraph is not called flattened`() = runTest {
        val browser = noteBrowser().apply { pasteFlattens = true }
        val result = routine(browser, note).run()

        assertThat(result.reason).isNotEqualTo("body-flattened")
    }

    // ── In-body image anchors ──

    private val second = DraftImage("b.jpg", "image/jpeg", "BBBB")
    private val anchoredHtml = "<p>Body text here</p><p>{{closepaw-image-2}}</p><p>More</p>"
    private val marker2 = "{{closepaw-image-2}}"

    @Test
    fun `anchors are found only in their own paragraph, the header's included`() {
        val html = "<p>{{closepaw-image-1}}</p><p class=\"x\"> {{closepaw-image-2}} </p>" +
            "<p>see {{closepaw-image-3}}</p><P>{{closepaw-image-4}}</P><p>{{closepaw-image-9}}</p>"
        assertThat(draftAnchoredImages(html, 4)).containsExactly(0, 1, 3)
        assertThat(draftAnchoredImages("<p>x</p>", 3)).isEmpty()
        assertThat(draftAnchorMarker(2)).isEqualTo("{{closepaw-image-2}}")
    }

    private val marker1 = "{{closepaw-image-1}}"
    private val headerAnchored = "<p>{{closepaw-image-1}}</p><h2>Head</h2><p>Body text here</p><p>{{closepaw-image-2}}</p>"

    @Test
    fun `an anchored header goes after the body, which goes into the empty editor first`() = runTest {
        val browser = noteBrowser().apply {
            anchorsInEditor[marker1] = 1
            anchorsInEditor[marker2] = 1
        }
        val result = routine(browser, note, listOf(image, second), html = headerAnchored).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.anchors).containsExactly("1:placed", "2:placed").inOrder()
        assertThat(result.imagesPlaced).isEqualTo(2)
        // Nothing before the body: no leading image, no caret moved after images.
        assertThat(browser.scripts).doesNotContain(DraftScript.PASTE_IMAGE)
        assertThat(browser.scripts).doesNotContain(DraftScript.CARET_AFTER_IMAGES)
        assertThat(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
            .isLessThan(browser.scripts.indexOf(DraftScript.ANCHOR_SELECT))
        assertThat(browser.scripts.count { it == DraftScript.PASTE_IMAGE_HERE }).isEqualTo(2)
        assertThat(info).containsAtLeast(
            "anchor 1: found=1",
            "anchor 1: backspace left=0 caretEmpty=true atEnd=true",
            "anchor 1: caret emptyP=true inFigure=false",
            "anchor 1: image placed",
            "anchor 2: found=1",
        ).inOrder()
    }

    @Test
    fun `an emptied anchor merged into the block before gets a fresh paragraph by Enter`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            anchorsInEditor[marker2] = 1
            mergeOnBackspace = true
        }
        val result = routine(browser, note, listOf(image, second), html = anchoredHtml).run()

        assertThat(result.anchors).containsExactly("2:placed")
        assertThat(browser.enters).isEqualTo(1)
        assertThat(info).contains("anchor 2: enter")
    }

    @Test
    fun `a merge that leaves text after the caret gets no Enter and no image`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            anchorsInEditor[marker2] = 1
            mergeOnBackspace = true
            mergeAtEnd = false
        }
        val result = routine(browser, note, listOf(image, second), html = anchoredHtml).run()

        assertThat(result.anchors).containsExactly("2:failed")
        assertThat(browser.enters).isEqualTo(0)
        assertThat(browser.scripts).doesNotContain(DraftScript.PASTE_IMAGE_HERE)
        assertThat(browser.anchorsInEditor[marker2]).isEqualTo(0)
    }

    @Test
    fun `an anchored image goes where its anchor is, after the body`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            anchorsInEditor[marker2] = 1
        }
        val result = routine(browser, note, listOf(image, second), html = anchoredHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.imagesExpected).isEqualTo(2)
        assertThat(result.imagesPlaced).isEqualTo(2)
        assertThat(result.anchors).containsExactly("2:placed")
        assertThat(result.toSummary()).contains("\"anchors\":[\"2:placed\"]")
        // Only the header goes before the body.
        assertThat(browser.scripts.count { it == DraftScript.PASTE_IMAGE }).isEqualTo(1)
        val order = listOf(
            browser.scripts.indexOf(DraftScript.PASTE_IMAGE),
            browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE),
            browser.scripts.indexOf(DraftScript.ANCHOR_SELECT),
            browser.scripts.indexOf(DraftScript.PASTE_IMAGE_HERE),
        )
        assertThat(order).isInOrder()
        assertThat(browser.backspaces).isEqualTo(1)
        assertThat(browser.anchorsInEditor[marker2]).isEqualTo(0)
        assertThat(info).contains("anchors 2:placed images placed=2/2")
    }

    @Test
    fun `a missing anchor leaves the image out and the body as it is`() = runTest {
        val browser = noteBrowser().apply { imageRouteWorks = DraftScript.PASTE_IMAGE }
        val result = routine(browser, note, listOf(image, second), html = anchoredHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(result.anchors).containsExactly("2:missing")
        assertThat(browser.scripts).doesNotContain(DraftScript.PASTE_IMAGE_HERE)
        assertThat(browser.backspaces).isEqualTo(0)
    }

    @Test
    fun `an anchor found twice is missing, and both marker paragraphs are removed`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            anchorsInEditor[marker2] = 2
        }
        val result = routine(browser, note, listOf(image, second), html = anchoredHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.anchors).containsExactly("2:missing")
        assertThat(browser.anchorsInEditor[marker2]).isEqualTo(0)
        assertThat(browser.scripts).doesNotContain(DraftScript.PASTE_IMAGE_HERE)
    }

    @Test
    fun `a failed upload leaves the image out, and the marker is gone`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            anchorsInEditor[marker2] = 1
            imageHereWorks = false
        }
        val result = routine(browser, note, listOf(image, second), html = anchoredHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.imagesPlaced).isEqualTo(1)
        assertThat(result.anchors).containsExactly("2:failed")
        assertThat(browser.anchorsInEditor[marker2]).isEqualTo(0)
        // The text, then the empty paragraph it left.
        assertThat(browser.backspaces).isEqualTo(2)
        assertThat(browser.caretEmpty).isFalse()
    }

    @Test
    fun `a marker that will not go away fails the read-back`() = runTest {
        val browser = noteBrowser().apply {
            imageRouteWorks = DraftScript.PASTE_IMAGE
            anchorsInEditor[marker2] = 1
            backspaceWorks = false
        }
        val result = routine(browser, note, listOf(image, second), html = anchoredHtml).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.UNCERTAIN)
        assertThat(result.reason).isEqualTo("read-back-mismatch")
        assertThat(result.bodyOk).isFalse()
        assertThat(result.anchors).containsExactly("2:failed")
        assertThat(browser.scripts).doesNotContain(DraftScript.PASTE_IMAGE_HERE)
    }

    @Test
    fun `without anchors every image leads the article, as before`() = runTest {
        val browser = noteBrowser().apply { imageRouteWorks = DraftScript.PASTE_IMAGE }
        val result = routine(browser, note, listOf(image, second)).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(result.imagesPlaced).isEqualTo(2)
        assertThat(result.anchors).isEmpty()
        assertThat(browser.scripts.count { it == DraftScript.PASTE_IMAGE }).isEqualTo(2)
        assertThat(browser.scripts.lastIndexOf(DraftScript.PASTE_IMAGE))
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
        assertThat(browser.scripts).doesNotContain(DraftScript.ANCHOR_SELECT)
    }

    // ── Trusted paste ──

    @Test
    fun `the body goes in by the page's clipboard and the paste key, and the clipboard is emptied`() = runTest {
        val browser = noteBrowser()
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.pasteKeys).isEqualTo(1)
        assertThat(browser.androidClips).isEqualTo(0)
        assertThat(browser.scripts).doesNotContain(DraftScript.INSERT_HTML)
        assertThat(browser.clipboardClears).isEqualTo(1)
        assertThat(browser.scripts.indexOf(DraftScript.FOCUS_TARGET))
            .isLessThan(browser.scripts.indexOf(DraftScript.CLIPBOARD_WRITE))
    }

    @Test
    fun `a refused page clipboard falls back to the Android clipboard`() = runTest {
        val browser = noteBrowser().apply { pageClipboardWorks = false }
        val result = routine(browser, note).run()

        assertThat(result.outcome).isEqualTo(DraftOutcome.SAVED)
        assertThat(browser.androidClips).isEqualTo(1)
        assertThat(browser.pasteKeys).isEqualTo(1)
        assertThat(info.any { it.startsWith("body method=paste clip=android pageWhy=NotAllowedError chars=14") }).isTrue()
    }

    @Test
    fun `with no clipboard at all no key is pressed and the last resort is reported`() = runTest {
        val browser = noteBrowser().apply {
            pageClipboardWorks = false
            androidClipboardWorks = false
        }
        val result = routine(browser, note, html = threeBlocks).run()

        assertThat(browser.pasteKeys).isEqualTo(0)
        assertThat(info).contains("body method=paste clip=none pageWhy=NotAllowedError")
        assertThat(info).contains("body method=paste-again clip=none pageWhy=NotAllowedError")
        assertThat(result.reason).isEqualTo("body-flattened")
    }

    @Test
    fun `the source's blocks are counted for the read-back`() {
        assertThat(draftSourceBlocks("<h2>A</h2><p>b</p><ul><li>c</li><li class=\"x\">d</li></ul><pre>e</pre>"))
            .isEqualTo(4)
        assertThat(draftSourceBlocks("<P>x</P>")).isEqualTo(1)
    }

    @Test
    fun `only the clipboard write gets a user gesture`() {
        DraftScript.values().forEach { script ->
            val params = draftEvaluateParams(script, JsonObject(emptyMap()))
            assertThat(params.containsKey("userGesture")).isEqualTo(script == DraftScript.CLIPBOARD_WRITE)
        }
    }

    @Test
    fun `the read-back is told how many blocks to expect`() = runTest {
        var blocks: String? = null
        val inner = noteBrowser()
        val browser = object : DraftBrowser by inner {
            override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
                if (script == DraftScript.READ_BACK) blocks = args["blocks"]?.jsonPrimitive?.content
                return inner.evaluate(tabId, script, args)
            }
        }
        routine(browser, note).run()
        assertThat(blocks).isEqualTo("1")
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
