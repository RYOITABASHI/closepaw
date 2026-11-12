package ai.closepaw.draft

import android.util.Log
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class DraftTab(val id: String, val url: String)

/** The only keys the routine presses besides the paste key. */
enum class DraftKey(val key: String, val keyCode: Int) {
    BACKSPACE("Backspace", 8),
    ENTER("Enter", 13),
    DELETE("Delete", 46),
}

/** What the routine may do to Chrome. Scripts are a closed enum; URLs come from [DraftSite]. */
interface DraftBrowser {
    /** Brings Chrome onto the run's display, opening nothing. CDP needs it visible. */
    suspend fun showChrome()

    /**
     * Gets Chrome ready on the run's display without showing it to the owner (see
     * [DraftChromeStage]). Returns null to go ahead, or the reason the run must not start, in
     * which case nothing has been done to the page. The default does nothing.
     */
    suspend fun prepareChrome(): String? = null

    /** How many JavaScript dialogs the browser has answered for this run so far. */
    fun dialogsAnswered(): Int = 0

    /** The run's own tab, which the browser closes on every exit. The default does nothing. */
    fun runTab(tabId: String) {}

    /**
     * Puts [html] on the Android clipboard, with a plain-text alternative, when the page could not
     * write it itself. False when that is not possible. The default does nothing.
     */
    suspend fun setClipboard(html: String): Boolean = false

    /** Empties the clipboard once the run has used it. The default does nothing. */
    suspend fun clearClipboard() {}

    /**
     * A trusted paste in [tabId]: the paste key, which the browser turns into its own paste
     * command from the clipboard. The default does nothing.
     */
    suspend fun pasteKey(tabId: String) {}

    /** One trusted key press in [tabId], from a closed set. The default does nothing. */
    suspend fun pressKey(tabId: String, key: DraftKey) {}

    /** Makes [tabId] the shown tab of its window, which is on the run's display. */
    suspend fun bringToFront(tabId: String) {}

    /**
     * One left click at a viewport point of [tabId]. The routine only clicks a point that
     * [DraftScript.FOCUS_TARGET] has just shown to be inside a plain block of the body.
     */
    suspend fun click(tabId: String, x: Double, y: Double) {}

    /** Asks Chrome over CDP for a new tab at [url] and returns its target id. */
    suspend fun createTab(url: String): String

    /** Opens [url] with an activity intent. See [SaveDraftRoutine.openOwnTab]. */
    suspend fun open(url: String)

    suspend fun tabs(): List<DraftTab>

    suspend fun navigate(tabId: String, url: String)

    /** Runs a fixed script and returns its result object. Throws when the script threw. */
    suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject

    /** Best effort; the result never depends on it. */
    suspend fun close(tabId: String)
}

enum class DraftOutcome(val status: String) {
    SAVED("completed"),
    ALREADY_EXISTS("completed"),
    UNCERTAIN("uncertain"),
    FAILED("failed"),
}

data class SaveDraftResult(
    val outcome: DraftOutcome,
    val platform: DraftPlatform,
    val url: String?,
    val reason: String?,
    val wrote: Boolean,
    val titleOk: Boolean,
    val bodyOk: Boolean,
    val saved: Boolean,
    val saveEvidence: String?,
    val dedupe: String,
    val imagesExpected: Int,
    val imagesPlaced: Int,
    val tabClosed: Boolean,
    /** One entry per in-body anchor: `"<n>:placed"`, `"<n>:missing"` or `"<n>:failed"`. */
    val anchors: List<String> = emptyList(),
    /** note's cover image: `placed`, `failed`, or `absent` when the request asked for none. */
    val cover: String = COVER_ABSENT,
) {
    /** The callback summary. Holds no article text. */
    fun toSummary(): String = buildJsonObject {
        put("kind", "save_draft")
        put("platform", platform.wire)
        put("outcome", outcome.name.lowercase())
        put("url", url)
        put("reason", reason)
        put("wrote", wrote)
        put("title_ok", titleOk)
        put("body_ok", bodyOk)
        put("saved", saved)
        put("save_evidence", saveEvidence)
        put("dedupe", dedupe)
        put("images_expected", imagesExpected)
        put("images_placed", imagesPlaced)
        put("tab_closed", tabClosed)
        put("anchors", buildJsonArray { anchors.forEach { add(JsonPrimitive(it)) } })
        put("cover", cover)
    }.toString()
}

data class SaveDraftTiming(
    val totalMs: Long = 150_000L,
    val pollMs: Long = 1_000L,
    val tabWaitMs: Long = 25_000L,
    val listWaitMs: Long = 8_000L,
    val editorWaitMs: Long = 30_000L,
    val imageWaitMs: Long = 20_000L,
    val saveWaitMs: Long = 30_000L,
)

/**
 * The fixed, LLM-free way to leave an article as a draft:
 * look for the title in the draft list, open the new-draft URL, set the title, paste the body,
 * save if the editor needs it, read back, report once.
 *
 * It stops rather than guesses:
 * - It acts only on a tab it can prove it created ([openOwnTab]), never on a restored one.
 * - Nothing is written unless the tab is on the site's editor URL and the editor is empty.
 * - Every write and the click re-check that URL in the page, and stop if the owner has Chrome.
 * - A step is never repeated after a write, so a timeout is `uncertain`: the draft may exist, and
 *   only the owner or a read of the draft list can tell.
 *
 * A confirmed save closes the tab here. On every exit the browser also closes the run's tab and
 * puts the owner's Chrome back where it was (ChromeDraftBrowser.stop, [DraftChromeStage]), so
 * nothing the run opened is left in the owner's Chrome.
 */
class SaveDraftRoutine(
    private val browser: DraftBrowser,
    private val request: SaveDraftRequest,
    /** True when the owner is, or may be, using Chrome. Must answer true when it cannot tell. */
    private val ownerHasChrome: () -> Boolean,
    private val timing: SaveDraftTiming = SaveDraftTiming(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newMarker: () -> String = ::randomMarker,
    /** Gets the class and the step, never page or article text. */
    private val logFailure: (String, Throwable) -> Unit = { message, e -> Log.w(TAG, message, e) },
    /** Step outcomes: flags, counts and lengths only, never page or article text. */
    private val logInfo: (String) -> Unit = { message -> Log.i(TAG, message) },
) {
    private val site = request.site

    // Read by [resultSoFar] after a cancellation, from another coroutine.
    @Volatile private var url: String? = null
    @Volatile private var wrote = false
    @Volatile private var titleOk = false
    @Volatile private var bodyOk = false
    @Volatile private var saved = false
    @Volatile private var saveEvidence: String? = null
    @Volatile private var dedupe = "not-checked"
    @Volatile private var imagesPlaced = 0
    @Volatile private var tabClosed = false

    /** Outcomes of in-body image anchors, in image order. */
    private val anchors = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var cover = COVER_ABSENT

    /** True when a body write ended up in the title field (which is then put back). */
    @Volatile private var titleOverwritten = false

    /** True when the body arrived without its structure (measured on the body). */
    @Volatile private var flattened = false

    /** The step in progress, for the reason and the log when something throws. */
    @Volatile private var step = STEP_CHROME

    /** Ends the run from any depth, past [pollUntil]. */
    private class Halt(val reason: String) : Exception()

    suspend fun run(): SaveDraftResult {
        val finished = withTimeoutOrNull(timing.totalMs) {
            try {
                steps()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Halt) {
                resultSoFar(e.reason)
            } catch (e: Exception) {
                val at = step
                logFailure("draft routine failed at step=$at: ${e.javaClass.name}", e)
                // Class name only: exception messages can carry page or request text.
                resultSoFar(draftErrorReason(e, at))
            }
        }
        return finished ?: result(DraftOutcome.UNCERTAIN, "timeout")
    }

    /** What is known so far, without touching the browser: for a run stopped from outside. */
    fun resultSoFar(reason: String): SaveDraftResult =
        result(if (wrote) DraftOutcome.UNCERTAIN else DraftOutcome.FAILED, reason)

    private fun result(outcome: DraftOutcome, reason: String?) = SaveDraftResult(
        outcome = outcome,
        platform = site.platform,
        url = url,
        reason = reason,
        wrote = wrote,
        titleOk = titleOk,
        bodyOk = bodyOk,
        saved = saved,
        saveEvidence = saveEvidence,
        dedupe = dedupe,
        imagesExpected = request.images.size,
        imagesPlaced = imagesPlaced,
        tabClosed = tabClosed,
        anchors = anchors.toList(),
        cover = cover,
    )

    private suspend fun steps(): SaveDraftResult {
        step = STEP_CHROME
        browser.prepareChrome()?.let { refusal ->
            logInfo("chrome not prepared: $refusal")
            return result(DraftOutcome.FAILED, refusal)
        }
        browser.showChrome()
        pollUntil(timing.tabWaitMs) { browser.tabs() }
            ?: return result(DraftOutcome.UNCERTAIN, "chrome-not-reachable")

        step = STEP_LIST
        val tab = openOwnTab(site.draftListUrl)
            ?: return result(DraftOutcome.UNCERTAIN, "tab-not-found")
        browser.runTab(tab.id)
        logInfo("tab ready")

        val copy = findExisting(tab)
        if (copy != null) {
            url = copy
            closeTab(tab)
            return result(DraftOutcome.ALREADY_EXISTS, null)
        }

        step = STEP_EDITOR
        stopIfOwnerHasChrome()
        browser.navigate(tab.id, site.newDraftUrl)
        val editor = pollUntil(timing.editorWaitMs) {
            browser.evaluate(tab.id, DraftScript.EDITOR_PROBE, NO_ARGS).takeIf { probe ->
                site.isEditorUrl(probe.str("href").orEmpty()) && probe.flag("titleFound") && probe.flag("bodyFound")
            }
        } ?: return result(DraftOutcome.UNCERTAIN, "editor-not-reached")
        val editorUrl = editor.str("href")!!
        url = editorUrl
        if (!editor.flag("titleEmpty") || !editor.flag("bodyEmpty")) {
            return result(DraftOutcome.FAILED, "editor-not-empty")
        }
        logInfo("editor reached")

        step = STEP_TITLE
        val titleSet = write(tab, editorUrl, DraftScript.SET_TITLE) { put("title", request.title) }
        if (!titleSet.flag("ok")) return result(DraftOutcome.UNCERTAIN, "title-not-set")
        logInfo("title set")

        // Images first: the caret is at the end of an empty body, so they lead the article. An
        // image whose anchor paragraph is in the body (the header's included) waits for the body
        // and goes to its anchor instead.
        step = STEP_IMAGE
        val coverMarker = draftHasCoverMarker(request.html)
        val asCover = coverMarker && site.platform == DraftPlatform.NOTE && request.images.isNotEmpty()
        val anchored = draftAnchoredImages(request.html, request.images.size).toMutableSet()
        if (coverMarker && !asCover && request.images.isNotEmpty()) anchored += 0
        if (asCover) anchored -= 0
        request.images.forEachIndexed { index, image ->
            if (index !in anchored && !(asCover && index == 0)) placeImage(tab, editorUrl, image)
        }
        // A paste while an image is still uploading gets note's "uploading" alert and no body.
        if (request.images.isNotEmpty()) {
            waitForUploads(tab)
            logInfo("images placed=$imagesPlaced/${request.images.size}")
        }

        step = STEP_PASTE
        val afterImages = imagesPlaced > 0
        pasteBody(tab, editorUrl, afterImages)?.let { refusal -> return result(DraftOutcome.UNCERTAIN, refusal) }

        if (anchored.isNotEmpty()) {
            step = STEP_ANCHOR
            anchored.sorted().forEach { index ->
                val marker = if (index == 0 && coverMarker && !asCover) COVER_MARKER else draftAnchorMarker(index + 1)
                placeAnchored(tab, editorUrl, index, request.images[index], marker)
            }
            logInfo("anchors ${anchors.joinToString(",")} images placed=$imagesPlaced/${request.images.size}")
        }
        if (coverMarker && (asCover || request.images.isEmpty())) {
            step = STEP_COVER
            removeMarker(tab, editorUrl, COVER_MARKER)
            if (asCover) placeCover(tab, editorUrl, request.images[0])
        }

        val confirmation = saveAndConfirm(tab, editorUrl)
        if (confirmation.outcome != DraftOutcome.SAVED) return confirmation
        closeTab(tab)
        return confirmation.copy(tabClosed = tabClosed)
    }

    /**
     * A tab this run can prove it created. Chrome lists the owner's restored tabs as ordinary
     * targets, so "a tab that was not there before" proves nothing when Chrome has just started.
     *
     * `Target.createTarget` answers with the new target's id. Where Android refuses it, as it does
     * `/json/new`, the tab comes from an activity intent instead, carrying a random fragment that
     * this run just made up: only the tab with that exact fragment is ours. The fragment never
     * leaves the device, because browsers do not send it to the server.
     */
    private suspend fun findMarked(marker: String, limitMs: Long): DraftTab? =
        pollUntil(limitMs) { browser.tabs().firstOrNull { it.url.endsWith(marker) } }

    private suspend fun openOwnTab(url: String): DraftTab? {
        val created = try {
            browser.createTab(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (created != null) return DraftTab(created, url)

        val marker = "#" + newMarker()
        browser.open(url + marker)
        return findMarked(marker, timing.tabWaitMs)
    }

    /**
     * The editor URL of a draft with this title, or null. Only a list row that shows the exact
     * title and links to this site's editor counts. If no row with an editor link rendered, the
     * list says nothing: that is `unknown`, not "no such draft". The article text cannot match
     * here, because nothing has been written and only this site's editor links qualify.
     */
    private suspend fun findExisting(tab: DraftTab): String? {
        var rendered = false
        val args = buildJsonObject { put("title", request.title) }
        val hit = pollUntil(timing.listWaitMs) {
            val probe = browser.evaluate(tab.id, DraftScript.LIST_PROBE, args)
            if (!site.isListUrl(probe.str("href").orEmpty())) return@pollUntil null
            val rows = probe["rows"]?.jsonArray.orEmpty().map { it.jsonObject }
            rendered = rendered || rows.any { row -> editorLinks(row).isNotEmpty() }
            rows.firstOrNull { it.flag("match") }?.let { editorLinks(it).firstOrNull() }
        }
        dedupe = when {
            hit != null -> "found"
            rendered -> "not-seen"
            else -> "unknown"
        }
        return hit
    }

    private fun editorLinks(row: JsonObject): List<String> =
        (row["hrefs"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.filter(site::isEditorUrl)

    /**
     * An image has landed when the site holds one more stored image than before and no upload is
     * running. A local preview shows up at once, so counting it would call an image placed while
     * note is still uploading it.
     */
    private suspend fun placeImage(tab: DraftTab, editorUrl: String, image: DraftImage) {
        for (route in listOf(DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE)) {
            val before = imageState(tab)["count"]?.jsonPrimitive?.intOrNull ?: 0
            val answer = write(tab, editorUrl, route) {
                put("name", image.name)
                put("mime", image.mime)
                put("base64", image.base64)
            }
            if (!answer.flag("ok")) continue
            var last: JsonObject? = null
            val landed = pollUntil(timing.imageWaitMs) {
                val state = imageState(tab).also { last = it }
                true.takeIf { draftImageLanded(before, state) }
            }
            if (landed == true) {
                imagesPlaced++
                return
            }
            // Still uploading: the second route would add a second copy once this one finishes.
            if (last?.flag("uploading") == true) return
        }
    }

    private suspend fun imageState(tab: DraftTab): JsonObject =
        browser.evaluate(tab.id, DraftScript.IMAGE_COUNT, NO_ARGS)

    /** Waits, within the image budget, for no upload to be running. Gives up quietly. */
    private suspend fun waitForUploads(tab: DraftTab) {
        pollUntil(timing.imageWaitMs) { true.takeUnless { imageState(tab).flag("uploading") } }
    }

    /**
     * Puts the body in, each attempt only into a body shown empty just before, so the article is
     * never doubled:
     * 1. a trusted paste (the HTML on the clipboard, then the paste key);
     * 2. if the body is still empty after any upload has finished (note refuses a paste while an
     *    image uploads), the trusted paste once more;
     * 3. as the last resort, the browser's insert command, which keeps the text but, in note,
     *    not the structure: the run then reports `body-flattened`.
     * No attempt goes in with the caret in an image's figure or caption (`focus-in-figure`), and a
     * paste that arrives as one paragraph is reported flattened without a further attempt.
     * Returns null when the body went in, else the reason. The clipboard is emptied afterwards.
     */
    private suspend fun pasteBody(tab: DraftTab, editorUrl: String, afterImages: Boolean): String? {
        val dialogsBefore = browser.dialogsAnswered()
        try {
            val first = trustedPaste(tab, editorUrl, "paste", dialogsBefore, afterImages)
            if (first == Attempt.REFUSED) return refusal
            if (first == Attempt.SENT && bodyFilledSoon(tab, editorUrl)) return checkStructure(tab)
            waitForUploads(tab)
            if (!bodyEmpty(tab, editorUrl)) return checkStructure(tab)
            val again = trustedPaste(tab, editorUrl, "paste-again", dialogsBefore, afterImages)
            if (again == Attempt.REFUSED) return refusal
            if (again == Attempt.SENT && bodyFilledSoon(tab, editorUrl)) return checkStructure(tab)
            if (!bodyEmpty(tab, editorUrl)) return checkStructure(tab)
        } finally {
            clearClipboard()
        }

        focusEditor(tab, editorUrl)
        if (afterImages) placeCaretAfterImages(tab, editorUrl)
        caretRefusal(tab, editorUrl, afterImages)?.let { reason ->
            logInfo("body method=insert refused=$reason")
            return reason
        }
        val inserted = write(tab, editorUrl, DraftScript.INSERT_HTML) { put("html", request.html) }
        logInfo(
            "body method=insert ok=${inserted.flag("ok")} accepted=${inserted.flag("accepted")} " +
                shapeLog(tab) + " dialogs=${browser.dialogsAnswered() - dialogsBefore}",
        )
        checkTitle(tab, editorUrl, "insert")
        // Judged by what the body holds, not by the method: an insert that kept the structure
        // in the body is a good body.
        if (inserted.flag("ok") && !bodyEmpty(tab, editorUrl)) checkStructure(tab)
        return if (inserted.flag("ok") || inserted.flag("notEmpty")) null else "body-not-pasted"
    }

    private enum class Attempt { SENT, NO_CLIPBOARD, REFUSED }

    /** Why the last attempt was refused before anything was written. */
    private var refusal: String = FOCUS_NOT_BODY

    /**
     * Focus, the caret checked (and, after leading images, moved into an empty paragraph after
     * them), the HTML onto the clipboard (the page's clipboard API, else the Android clipboard),
     * then the paste key. Never pastes with the caret in an image's figure or caption.
     */
    private suspend fun trustedPaste(
        tab: DraftTab,
        editorUrl: String,
        method: String,
        dialogsBefore: Int,
        afterImages: Boolean,
    ): Attempt {
        focusEditor(tab, editorUrl)
        if (afterImages) placeCaretAfterImages(tab, editorUrl)
        caretRefusal(tab, editorUrl, afterImages)?.let { reason ->
            refusal = reason
            logInfo("body method=$method refused=$reason")
            return Attempt.REFUSED
        }
        stopIfOwnerHasChrome()
        val written = browser.evaluate(tab.id, DraftScript.CLIPBOARD_WRITE, buildJsonObject {
            put("editorUrl", editorUrl)
            put("html", request.html)
        })
        stopIfMoved(written)
        val clip = when {
            written.flag("ok") -> "page"
            browser.setClipboard(request.html) -> "android"
            else -> null
        }
        val pageWhy = written.str("why")?.let { " pageWhy=$it" }.orEmpty()
        if (clip == null) {
            logInfo("body method=$method clip=none$pageWhy")
            return Attempt.NO_CLIPBOARD
        }
        stopIfOwnerHasChrome()
        wrote = true
        browser.pasteKey(tab.id)
        logInfo(
            "body method=$method clip=$clip$pageWhy " + shapeLog(tab) +
                " dialogs=${browser.dialogsAnswered() - dialogsBefore}",
        )
        checkTitle(tab, editorUrl, method)
        return Attempt.SENT
    }

    /**
     * After leading images the caret may be left in the image's caption or on the figure, and a
     * paste there becomes one long caption paragraph. Puts the caret in the first empty paragraph
     * after the last image; if there is none, selects the image block whole and presses Enter,
     * which makes one after it, then puts the caret there.
     */
    private suspend fun placeCaretAfterImages(tab: DraftTab, editorUrl: String) {
        val placed = write(tab, editorUrl, DraftScript.CARET_AFTER_IMAGES) { put("mode", "place") }
        if (placed.flag("ok")) {
            logInfo("caret after images: ${placed.str("kind")}")
            return
        }
        if (placed.str("kind") != "figure") {
            logInfo("caret after images: ${placed.str("kind")}")
            return
        }
        val around = write(tab, editorUrl, DraftScript.CARET_AFTER_IMAGES) { put("mode", "select-figure") }
        if (!around.flag("ok")) {
            logInfo("caret after images: figure not selected")
            return
        }
        stopIfOwnerHasChrome()
        browser.pressKey(tab.id, DraftKey.ENTER)
        val again = write(tab, editorUrl, DraftScript.CARET_AFTER_IMAGES) { put("mode", "place") }
        logInfo("caret after images: ${if (again.flag("ok")) "new-p" else "none"}")
    }

    /**
     * Null when the caret may take the body; otherwise where it is. Never in a figure or caption,
     * and after leading images only in an empty paragraph of its own.
     */
    private suspend fun caretRefusal(tab: DraftTab, editorUrl: String, afterImages: Boolean): String? {
        var probe = browser.evaluate(tab.id, DraftScript.FOCUS_PROBE, NO_ARGS)
        val first = probe.str("where") ?: "none"
        if (first != "body" && first != "figure" || !probe.flag("focused")) {
            // An empty body may offer nothing to click (build 41: the pastes went to the title).
            val focused = write(tab, editorUrl, DraftScript.FOCUS_BODY) {}
            probe = browser.evaluate(tab.id, DraftScript.FOCUS_PROBE, NO_ARGS)
            logInfo("caret where=$first -> ${probe.str("where")} focusBody=${focused.flag("ok")} focused=${probe.flag("focused")}")
        }
        return when {
            probe.flag("inFigure") || probe.str("where") == "figure" -> FOCUS_IN_FIGURE
            probe.str("where") != "body" || !probe.flag("focused") -> FOCUS_NOT_BODY
            afterImages && !probe.flag("caretEmptyP") -> FOCUS_IN_FIGURE
            else -> null
        }
    }

    /**
     * After a body write, the title field must still hold the requested title. A write that went
     * into it is the run's own, so the title is set back; the run then reports `title-overwritten`.
     */
    private suspend fun checkTitle(tab: DraftTab, editorUrl: String, method: String) {
        val shape = try {
            browser.evaluate(tab.id, DraftScript.BODY_SHAPE, buildJsonObject { put("title", request.title) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (shape["titleSame"]?.jsonPrimitive?.booleanOrNull != false) return
        titleOverwritten = true
        val restored = write(tab, editorUrl, DraftScript.SET_TITLE) { put("title", request.title) }.flag("ok")
        logInfo("title overwritten by method=$method; restored=$restored")
    }

    /**
     * A paste that left at most one block when the source has several lost its structure (the
     * editor put it all in one paragraph or caption): reported as flattened, with no further
     * attempt.
     */
    private suspend fun checkStructure(tab: DraftTab): String? {
        val want = draftSourceBlocks(request.html)
        if (want < FLAT_SOURCE_MIN_BLOCKS) return null
        val shape = try {
            browser.evaluate(tab.id, DraftScript.BODY_SHAPE, NO_ARGS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        fun n(key: String) = shape[key]?.jsonPrimitive?.intOrNull ?: 0
        val blocks = n("p") + n("h") + n("li")
        if (blocks <= 1) {
            flattened = true
            logInfo("body flattened: blocks=$blocks of $want")
        }
        return null
    }

    /** `chars=N p=N h=N li=N`: numbers only. */
    private suspend fun shapeLog(tab: DraftTab): String = try {
        val shape = browser.evaluate(tab.id, DraftScript.BODY_SHAPE, NO_ARGS)
        fun n(key: String) = shape[key]?.jsonPrimitive?.intOrNull ?: -1
        "chars=${n("chars")} p=${n("p")} h=${n("h")} li=${n("li")}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "shape=${e.javaClass.simpleName}"
    }

    private suspend fun clearClipboard() {
        try {
            browser.clearClipboard()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logInfo("clipboard not cleared: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Puts images[[index]] where its anchor paragraph (`{{closepaw-image-<index+1>}}`) is: the
     * paragraph's text is selected and deleted with a trusted Backspace, and the image is pasted
     * into the now empty paragraph, then awaited like the header. If there is not exactly one
     * anchor, or the text does not go, or the upload does not land, the anchor text is removed
     * and the image is left out: no anchor text may stay in a saved draft.
     */
    private suspend fun placeAnchored(tab: DraftTab, editorUrl: String, index: Int, image: DraftImage, marker: String) {
        val number = index + 1
        val selected = write(tab, editorUrl, DraftScript.ANCHOR_SELECT) { put("marker", marker) }
        logInfo("anchor $number: found=${selected.anchorCount()}")
        if (!selected.flag("ok")) {
            removeMarker(tab, editorUrl, marker)
            anchors += "$number:missing"
            return
        }
        stopIfOwnerHasChrome()
        browser.pressKey(tab.id, DraftKey.BACKSPACE)
        val emptied = anchorCheck(tab, editorUrl, marker)
        logInfo(
            "anchor $number: backspace left=${emptied.anchorCount()} caretEmpty=${emptied.flag("caretEmpty")} " +
                "atEnd=${emptied.flag("caretAtEnd")}",
        )
        if (emptied.anchorCount() != 0) {
            removeMarker(tab, editorUrl, marker)
            anchors += "$number:failed"
            return
        }
        if (!emptied.flag("caretEmpty")) {
            // The emptied paragraph was merged into the block before it. At the end of that block,
            // one Enter makes a fresh empty paragraph; anywhere else it would split text.
            if (!emptied.flag("caretAtEnd")) {
                anchors += "$number:failed"
                return
            }
            stopIfOwnerHasChrome()
            browser.pressKey(tab.id, DraftKey.ENTER)
            logInfo("anchor $number: enter")
        }
        val caret = browser.evaluate(tab.id, DraftScript.FOCUS_PROBE, NO_ARGS)
        logInfo("anchor $number: caret emptyP=${caret.flag("caretEmptyP")} inFigure=${caret.flag("inFigure")}")
        if (!caret.flag("caretEmptyP") || caret.flag("inFigure")) {
            anchors += "$number:failed"
            return
        }

        val before = imageState(tab)["count"]?.jsonPrimitive?.intOrNull ?: 0
        val answer = write(tab, editorUrl, DraftScript.PASTE_IMAGE_HERE) {
            put("name", image.name)
            put("mime", image.mime)
            put("base64", image.base64)
        }
        val landed = answer.flag("ok") &&
            pollUntil(timing.imageWaitMs) { true.takeIf { draftImageLanded(before, imageState(tab)) } } == true
        logInfo("anchor $number: image ${if (landed) "placed" else "not placed"}")
        if (landed) {
            imagesPlaced++
            anchors += "$number:placed"
            return
        }
        // The anchor text is already gone; drop the empty paragraph it leaves when that is safe.
        dropEmptyParagraph(tab, editorUrl, marker)
        anchors += "$number:failed"
    }

    /** Removes every paragraph that holds only [marker]: select its text, Backspace. */
    private suspend fun removeMarker(tab: DraftTab, editorUrl: String, marker: String) {
        repeat(MAX_MARKER_REMOVALS) {
            val selected = write(tab, editorUrl, DraftScript.ANCHOR_SELECT) {
                put("marker", marker)
                put("any", true)
            }
            if (!selected.flag("ok")) return
            stopIfOwnerHasChrome()
            browser.pressKey(tab.id, DraftKey.BACKSPACE)
            dropEmptyParagraph(tab, editorUrl, marker)
        }
    }

    /**
     * A second Backspace removes the empty paragraph the caret is in, but only when the block
     * before it is a plain paragraph or heading, so the Backspace cannot select an image.
     */
    private suspend fun dropEmptyParagraph(tab: DraftTab, editorUrl: String, marker: String) {
        val check = anchorCheck(tab, editorUrl, marker)
        if (!check.flag("caretEmpty")) return
        val key = when {
            check.flag("prevPlain") -> DraftKey.BACKSPACE
            // The first paragraph (a cover marker leads the body): join the block after into it.
            check.flag("afterPlain") -> DraftKey.DELETE
            else -> return
        }
        stopIfOwnerHasChrome()
        browser.pressKey(tab.id, key)
    }

    /**
     * note's cover image (見出し画像): the file goes to the editor's own cover input, any crop
     * dialog is pressed through with its default crop, and the cover counts once a stored image
     * shows above the title. A failure leaves the draft without a cover; the image never goes
     * into the body.
     */
    private suspend fun placeCover(tab: DraftTab, editorUrl: String, image: DraftImage) {
        val labels = buildJsonObject { put("labels", buildJsonArray { COVER_CROP_LABELS.forEach { add(JsonPrimitive(it)) } }) }
        val before = browser.evaluate(tab.id, DraftScript.COVER_STATE, labels)["count"]?.jsonPrimitive?.intOrNull ?: 0
        val handed = write(tab, editorUrl, DraftScript.COVER_FILE) {
            put("name", image.name)
            put("mime", image.mime)
            put("base64", image.base64)
        }
        logInfo(
            "cover: input ok=${handed.flag("ok")} inputs=${handed["total"]?.jsonPrimitive?.intOrNull ?: -1} " +
                "candidates=${handed["candidates"]?.jsonPrimitive?.intOrNull ?: -1}",
        )
        if (!handed.flag("ok")) {
            cover = COVER_FAILED
            return
        }
        var pressed = false
        val placed = pollUntil(timing.imageWaitMs) {
            val state = browser.evaluate(tab.id, DraftScript.COVER_STATE, labels)
            val count = state["count"]?.jsonPrimitive?.intOrNull ?: 0
            if (count > before && !state.flag("dialog")) return@pollUntil true
            val x = state["x"]?.jsonPrimitive?.doubleOrNull
            val y = state["y"]?.jsonPrimitive?.doubleOrNull
            if (!pressed && state.flag("pressable") && x != null && y != null) {
                stopIfOwnerHasChrome()
                browser.click(tab.id, x, y)
                pressed = true
                logInfo("cover: crop dialog pressed")
            }
            null
        } == true
        cover = if (placed) COVER_PLACED else COVER_FAILED
        if (placed) imagesPlaced++
        logInfo("cover: $cover")
    }

    private suspend fun anchorCheck(tab: DraftTab, editorUrl: String, marker: String): JsonObject {
        val check = browser.evaluate(tab.id, DraftScript.ANCHOR_CHECK, buildJsonObject { put("marker", marker) })
        if (check.str("href") != editorUrl) throw Halt("editor-moved")
        return check
    }

    private fun JsonObject.anchorCount(): Int = this["count"]?.jsonPrimitive?.intOrNull ?: -1

    /** Gives a slow editor a moment to show what it took. */
    private suspend fun bodyFilledSoon(tab: DraftTab, editorUrl: String): Boolean =
        pollUntil(minOf(timing.imageWaitMs, PASTE_SETTLE_MS)) {
            true.takeUnless { bodyEmpty(tab, editorUrl) }
        } == true

    /**
     * Gives the body real focus before text goes in: the tab is shown in its window (on the run's
     * display), a click lands in the body's last plain block or, failing that, in the body itself
     * ([DraftScript.FOCUS_TARGET]), and a probe reports where the focus ended up. Best effort: a failure here is logged and the paste goes ahead.
     */
    private suspend fun focusEditor(tab: DraftTab, editorUrl: String) {
        var front = false
        var clicked = false
        var why: String? = null
        try {
            stopIfOwnerHasChrome()
            // Chrome is on the run's display, so this shows the tab there, not to the owner.
            browser.bringToFront(tab.id)
            front = true
            val target = browser.evaluate(tab.id, DraftScript.FOCUS_TARGET, buildJsonObject { put("editorUrl", editorUrl) })
            stopIfMoved(target)
            val x = target["x"]?.jsonPrimitive?.doubleOrNull
            val y = target["y"]?.jsonPrimitive?.doubleOrNull
            why = target.str("why")
            if (target.flag("ok") && x != null && y != null) {
                stopIfOwnerHasChrome()
                browser.click(tab.id, x, y)
                clicked = true
            }
            val probe = browser.evaluate(tab.id, DraftScript.FOCUS_PROBE, NO_ARGS)
            logInfo(
                "focus front=$front clicked=$clicked target=${why ?: target.str("on") ?: "ok"} " +
                    "focused=${probe.flag("focused")} pageFocused=${probe.flag("pageFocused")}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Halt) {
            throw e
        } catch (e: Exception) {
            logInfo("focus front=$front clicked=$clicked failed=${e.javaClass.simpleName}")
        }
    }

    /** True only when the probe shows this draft's editor with an empty body. */
    private suspend fun bodyEmpty(tab: DraftTab, editorUrl: String): Boolean {
        val probe = browser.evaluate(tab.id, DraftScript.EDITOR_PROBE, NO_ARGS)
        if (probe.str("href") != editorUrl) throw Halt("editor-moved")
        return probe.flag("bodyFound") && probe.flag("bodyEmpty")
    }

    private suspend fun saveAndConfirm(tab: DraftTab, editorUrl: String): SaveDraftResult {
        step = STEP_SAVE
        val key = site.draftId(editorUrl) ?: return result(DraftOutcome.UNCERTAIN, "no-draft-id")
        val (probe, probeArgs) = when (site.platform) {
            DraftPlatform.SUBSTACK -> DraftScript.SUBSTACK_SAVED_PROBE to contentArgs(editorUrl, key, null)
            DraftPlatform.NOTE -> {
                val click = write(tab, editorUrl, DraftScript.NOTE_SAVE_CLICK) {}
                if (!click.flag("ok")) return result(DraftOutcome.UNCERTAIN, "save-control-not-unique")
                val at = click["at"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                DraftScript.NOTE_SAVED_PROBE to contentArgs(editorUrl, key, at)
            }
        }

        step = STEP_READBACK
        val confirmed = pollUntil(timing.saveWaitMs) {
            stopIfOwnerHasChrome()
            val back = browser.evaluate(tab.id, DraftScript.READ_BACK, contentArgs(editorUrl, key, null))
            stopIfMoved(back)
            titleOk = back.flag("titleOk")
            bodyOk = back.flag("bodyOk")
            if (titleOk && bodyOk) {
                val answer = browser.evaluate(tab.id, probe, probeArgs)
                stopIfMoved(answer)
                saved = answer.flag("saved")
                saveEvidence = answer.str("evidence")
            }
            true.takeIf { titleOk && bodyOk && saved }
        }
        if (titleOverwritten) {
            // The title was put back, but a write went where it should not: say so.
            return result(DraftOutcome.UNCERTAIN, TITLE_OVERWRITTEN)
        }
        if (flattened) {
            // The text may be stored, but not as the article: never report that as a success.
            bodyOk = false
            return result(DraftOutcome.UNCERTAIN, BODY_FLATTENED)
        }
        return when {
            confirmed == true -> result(DraftOutcome.SAVED, null)
            !titleOk || !bodyOk -> result(DraftOutcome.UNCERTAIN, "read-back-mismatch")
            else -> result(DraftOutcome.UNCERTAIN, "save-unconfirmed")
        }
    }

    /** The only way anything reaches the page. Checks first, marks the write, then acts. */
    private suspend fun write(
        tab: DraftTab,
        editorUrl: String,
        script: DraftScript,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        stopIfOwnerHasChrome()
        wrote = true
        val answer = browser.evaluate(tab.id, script, buildJsonObject {
            put("editorUrl", editorUrl)
            fields()
        })
        stopIfMoved(answer)
        return answer
    }

    private fun stopIfOwnerHasChrome() {
        if (ownerHasChrome()) throw Halt("chrome-opened")
    }

    private fun stopIfMoved(answer: JsonObject) {
        if (answer.flag("moved")) throw Halt("editor-moved")
    }

    private suspend fun closeTab(tab: DraftTab) {
        tabClosed = try {
            browser.close(tab.id)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Polls until [step] gives a value. A failed step is a miss: pages reload under CDP. */
    private suspend fun <T : Any> pollUntil(limitMs: Long, step: suspend () -> T?): T? {
        val deadline = now() + limitMs
        while (true) {
            val value = try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Halt) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (value != null) return value
            if (now() >= deadline) return null
            delay(timing.pollMs)
        }
    }

    private fun contentArgs(editorUrl: String, key: String, at: Double?) = buildJsonObject {
        put("editorUrl", editorUrl)
        put("key", key)
        put("title", request.title)
        // Anchor text is gone by now, so a sample that holds it cannot be found.
        put("samples", buildJsonArray { request.samples.filterNot { ANCHOR_TEXT in it }.forEach { add(JsonPrimitive(it)) } })
        put("blocks", draftSourceBlocks(request.html))
        at?.let { put("at", it) }
    }

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TAG = "SaveDraftRoutine"
        const val PASTE_SETTLE_MS = 3_000L
        const val MAX_MARKER_REMOVALS = 5
        val NO_ARGS = JsonObject(emptyMap())
        val RANDOM = SecureRandom()

        fun randomMarker(): String {
            val bytes = ByteArray(16).also(RANDOM::nextBytes)
            return "closepaw-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

internal const val STEP_CHROME = "chrome"
internal const val STEP_LIST = "list"
internal const val STEP_EDITOR = "editor"
internal const val STEP_TITLE = "title"
internal const val STEP_IMAGE = "image"
internal const val STEP_PASTE = "paste"
internal const val STEP_SAVE = "save"
internal const val STEP_READBACK = "readback"
internal const val STEP_ANCHOR = "anchor"
internal const val STEP_COVER = "cover"

/** Start of every anchor's text (images and the cover); none may remain in a saved draft. */
internal const val ANCHOR_TEXT = "{{closepaw-"

/** The anchor text for image number [number] (1-based; the header is 1). */
internal fun draftAnchorMarker(number: Int): String = "{{closepaw-image-$number}}"

/**
 * Indexes of images whose anchor paragraph, a `<p>` holding only `{{closepaw-image-<index+1>}}`,
 * is in the body HTML. The header (images[0], anchor 1) may have one too: then the body goes into
 * the empty editor first and every image is placed at its anchor afterwards.
 */
internal fun draftAnchoredImages(html: String, imageCount: Int): Set<Int> {
    // Android's ICU regex rejects an unescaped `}` (desktop Java accepts it; build 36 failed on device).
    val numbers = Regex("<p\\b[^>]*>\\s*\\{\\{closepaw-image-(\\d{1,3})\\}\\}\\s*</p>", RegexOption.IGNORE_CASE)
        .findAll(html)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .toSet()
    return (0 until imageCount).filter { (it + 1) in numbers }.toSet()
}

/** `error-<class>@<step>`: the class's simple name, never its message, which may hold page text. */
internal fun draftErrorReason(e: Throwable, step: String): String = "error-${e.javaClass.simpleName}@$step"

/** An [DraftScript.IMAGE_COUNT] answer with one more stored image than [before] and no upload running. */
internal fun draftImageLanded(before: Int, state: JsonObject): Boolean {
    val count = state["count"]?.jsonPrimitive?.intOrNull ?: return false
    val uploading = state["uploading"]?.jsonPrimitive?.booleanOrNull ?: true
    return count > before && !uploading
}

/**
 * The answer to a JavaScript dialog during a draft run. An alert has only OK. Anything else is
 * dismissed: a confirm could be asking to publish, and dismissing a beforeunload keeps the editor.
 */
internal fun draftDialogAccept(type: String): Boolean = type == "alert"

/** The CDP parameters for a fixed script: JSON in, JSON out, awaiting any promise. */
internal fun draftEvaluateParams(script: DraftScript, args: JsonObject): JsonObject = buildJsonObject {
    put("expression", script.expression(args))
    put("returnByValue", true)
    put("awaitPromise", true)
    // The clipboard API needs a user gesture; no other script gets one.
    if (script == DraftScript.CLIPBOARD_WRITE) put("userGesture", true)
}

/** The reason for a body that went in without its structure. */
internal const val BODY_FLATTENED = "body-flattened"

/** The reason for a run whose caret could not be put in the body, so nothing went in. */
internal const val FOCUS_NOT_BODY = "focus-not-body"

/** The reason for a run where a body write landed in the title field (then put back). */
internal const val TITLE_OVERWRITTEN = "title-overwritten"

internal const val COVER_ABSENT = "absent"
internal const val COVER_PLACED = "placed"
internal const val COVER_FAILED = "failed"

/** Marks the body's cover paragraph: images[0] becomes note's cover image (見出し画像). */
internal const val COVER_MARKER = "{{closepaw-cover-1}}"

/** Labels of the button that accepts a crop dialog's default crop. Exact, after trimming. */
internal val COVER_CROP_LABELS = listOf("保存", "適用", "完了", "決定", "OK")

internal fun draftHasCoverMarker(html: String): Boolean =
    Regex("<p\\b[^>]*>\\s*\\{\\{closepaw-cover-1\\}\\}\\s*</p>", RegexOption.IGNORE_CASE).containsMatchIn(html)

/** The reason for a run that would only have pasted into an image's figure or caption. */
internal const val FOCUS_IN_FIGURE = "focus-in-figure"

/** A source with at least this many blocks that arrives as one is flattened. */
internal const val FLAT_SOURCE_MIN_BLOCKS = 3

/** Paragraphs, headings and list items in the source HTML, for the read-back's rough check. */
internal fun draftSourceBlocks(html: String): Int =
    Regex("<(p|h[1-6]|li)[\\s>]", RegexOption.IGNORE_CASE).findAll(html).count()

class DraftScriptException(script: DraftScript) : Exception("draft script failed: ${script.name}")

/** The script's return value, from a `Runtime.evaluate` response. */
internal fun draftEvaluateResult(script: DraftScript, response: JsonElement): JsonObject {
    val body = response.jsonObject
    if ("exceptionDetails" in body) throw DraftScriptException(script)
    val value = body["result"]?.jsonObject?.get("value") as? JsonObject
    return value ?: throw DraftScriptException(script)
}
