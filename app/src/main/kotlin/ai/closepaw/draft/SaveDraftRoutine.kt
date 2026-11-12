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

    /** True when the body only went in through the last resort, which loses its structure. */
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

        // Images first: the caret is at the end of an empty body, so they lead the article.
        step = STEP_IMAGE
        request.images.forEach { placeImage(tab, editorUrl, it) }
        // A paste while an image is still uploading gets note's "uploading" alert and no body.
        if (request.images.isNotEmpty()) {
            waitForUploads(tab)
            logInfo("images placed=$imagesPlaced/${request.images.size}")
        }

        step = STEP_PASTE
        if (!pasteBody(tab, editorUrl)) return result(DraftOutcome.UNCERTAIN, "body-not-pasted")

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
     * The clipboard is emptied afterwards.
     */
    private suspend fun pasteBody(tab: DraftTab, editorUrl: String): Boolean {
        val dialogsBefore = browser.dialogsAnswered()
        try {
            if (trustedPaste(tab, editorUrl, "paste", dialogsBefore) && bodyFilledSoon(tab, editorUrl)) return true
            waitForUploads(tab)
            if (!bodyEmpty(tab, editorUrl)) return true
            if (trustedPaste(tab, editorUrl, "paste-again", dialogsBefore) && bodyFilledSoon(tab, editorUrl)) return true
            if (!bodyEmpty(tab, editorUrl)) return true
        } finally {
            clearClipboard()
        }

        focusEditor(tab, editorUrl)
        val inserted = write(tab, editorUrl, DraftScript.INSERT_HTML) { put("html", request.html) }
        // Flattened only if the insert really put text in.
        flattened = inserted.flag("ok") && !bodyEmpty(tab, editorUrl)
        logInfo(
            "body method=insert ok=${inserted.flag("ok")} accepted=${inserted.flag("accepted")} " +
                shapeLog(tab) + " dialogs=${browser.dialogsAnswered() - dialogsBefore}",
        )
        return inserted.flag("ok") || inserted.flag("notEmpty")
    }

    /**
     * Focus, the HTML onto the clipboard (the page's clipboard API, else the Android clipboard),
     * then the paste key. False when nothing could be put on the clipboard, so no key was sent.
     */
    private suspend fun trustedPaste(tab: DraftTab, editorUrl: String, method: String, dialogsBefore: Int): Boolean {
        focusEditor(tab, editorUrl)
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
            return false
        }
        stopIfOwnerHasChrome()
        wrote = true
        browser.pasteKey(tab.id)
        logInfo(
            "body method=$method clip=$clip$pageWhy " + shapeLog(tab) +
                " dialogs=${browser.dialogsAnswered() - dialogsBefore}",
        )
        return true
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
        put("samples", buildJsonArray { request.samples.forEach { add(JsonPrimitive(it)) } })
        put("blocks", draftSourceBlocks(request.html))
        at?.let { put("at", it) }
    }

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TAG = "SaveDraftRoutine"
        const val PASTE_SETTLE_MS = 3_000L
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
