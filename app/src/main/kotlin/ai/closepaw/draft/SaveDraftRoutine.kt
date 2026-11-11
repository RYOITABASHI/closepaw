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

/** Where [DraftBrowser.openOwnWindow] put Chrome. */
enum class DraftWindow {
    /** Nothing was started. */
    NONE,

    /** A new Chrome window task appeared on the run's virtual display. */
    VIRTUAL_DISPLAY,

    /** Chrome took the intent, but no new task appeared on the virtual display. */
    ELSEWHERE,
}

/** What the routine may do to Chrome. Scripts are a closed enum; URLs come from [DraftSite]. */
interface DraftBrowser {
    /** Brings Chrome onto the run's display, opening nothing. CDP needs it visible. */
    suspend fun showChrome()

    /**
     * Best effort: opens [url] in a new Chrome window task on the run's display, so the owner's
     * own Chrome window stays where it is. The default opens nothing.
     */
    suspend fun openOwnWindow(url: String): DraftWindow = DraftWindow.NONE

    /** How many JavaScript dialogs the browser has answered for this run so far. */
    fun dialogsAnswered(): Int = 0

    /**
     * The run drives [tabId], the marked tab of the window [openOwnWindow] put on the virtual
     * display, so the browser can close it on every exit. The default does nothing.
     */
    fun ownWindowTab(tabId: String) {}

    /** Makes [tabId] the shown tab of its window. Used only for the run's own window. */
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
 * Only a confirmed save closes the tab. Any other ending leaves it open in Chrome, with what it
 * holds, because a closed editor cannot be recovered; see ChromeDraftBrowser.stop for what happens
 * to the tab when the virtual display goes away.
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

    /** The step in progress, for the reason and the log when something throws. */
    @Volatile private var step = STEP_CHROME

    /**
     * True while the run works in its own Chrome window on the virtual display. The owner's Chrome
     * on the main display is then a different window that the run never touches, so it being in
     * front is no reason to stop: [stopIfOwnerHasChrome] and the service's window watch skip it.
     */
    @Volatile var drivesOwnWindow = false
        private set

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
        val windowMarker = "#" + newMarker()
        // Chrome's tabs before the window, when Chrome already answers: a tab that appears after
        // the window opened, alone and on the site, is the window's tab even if the page dropped
        // the fragment. Without this list only the fragment counts.
        val tabsBefore = try {
            browser.tabs().map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val window = try {
            browser.openOwnWindow(site.draftListUrl + windowMarker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DraftWindow.NONE
        }
        // In its own window on the virtual display Chrome is already visible there. Otherwise do
        // as before: bring Chrome to the display.
        drivesOwnWindow = window == DraftWindow.VIRTUAL_DISPLAY
        if (!drivesOwnWindow) browser.showChrome()
        pollUntil(timing.tabWaitMs) { browser.tabs() }
            ?: return result(DraftOutcome.UNCERTAIN, "chrome-not-reachable")

        step = STEP_LIST
        // The window's tab carries a fragment this run just made up, so it is provably ours.
        val windowTab = when (window) {
            DraftWindow.NONE -> null
            DraftWindow.VIRTUAL_DISPLAY -> findWindowTab(windowMarker, tabsBefore)
            DraftWindow.ELSEWHERE -> findMarked(windowMarker, minOf(timing.tabWaitMs, WINDOW_TAB_GLANCE_MS))
        }
        logInfo("window=$window tab=${windowTab != null}")
        if (windowTab != null && drivesOwnWindow) browser.ownWindowTab(windowTab.id)
        if (windowTab == null && drivesOwnWindow) {
            // The window's tab never showed: the fallback works in the owner's Chrome window, so
            // the guard applies again from here.
            drivesOwnWindow = false
            stopIfOwnerHasChrome()
        }
        val tab = windowTab ?: openOwnTab(site.draftListUrl)
            ?: return result(DraftOutcome.UNCERTAIN, "tab-not-found")

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

    /** The tab of the run's own window: the marked one, or the one new tab on the site. */
    private suspend fun findWindowTab(marker: String, before: Set<String>?): DraftTab? =
        pollUntil(timing.tabWaitMs) {
            val tabs = browser.tabs()
            tabs.firstOrNull { it.url.contains(marker) }
                ?: before?.let { known ->
                    tabs.filter { it.id !in known }.singleOrNull()?.takeIf { sameHost(it.url, site.draftListUrl) }
                }
        }

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
     * Pastes the body, after giving the editor real focus. When the body is still empty after the
     * paste, and after any upload has finished:
     * - if the page answered with a dialog meanwhile (note's "image uploading" alert), the paste is
     *   repeated once;
     * - if it is still empty, the same HTML goes in once through the browser's editing command.
     * Every second attempt goes only into a body shown empty just before, so the article is never
     * doubled.
     */
    private suspend fun pasteBody(tab: DraftTab, editorUrl: String): Boolean {
        val dialogsBefore = browser.dialogsAnswered()
        focusEditor(tab, editorUrl)
        val pasted = write(tab, editorUrl, DraftScript.PASTE_HTML) { put("html", request.html) }
        logPaste("paste", pasted, browser.dialogsAnswered() - dialogsBefore)
        if (!pasted.flag("ok")) return false
        if (bodyFilledSoon(tab, editorUrl)) return true
        waitForUploads(tab)
        if (!bodyEmpty(tab, editorUrl)) return true

        if (browser.dialogsAnswered() > dialogsBefore) {
            val again = write(tab, editorUrl, DraftScript.PASTE_HTML) { put("html", request.html) }
            logPaste("paste-again", again, browser.dialogsAnswered() - dialogsBefore)
            if (again.flag("ok") && bodyFilledSoon(tab, editorUrl)) return true
            if (!bodyEmpty(tab, editorUrl)) return true
        }

        focusEditor(tab, editorUrl)
        val inserted = write(tab, editorUrl, DraftScript.INSERT_HTML) { put("html", request.html) }
        logPaste("insert", inserted, browser.dialogsAnswered() - dialogsBefore)
        return inserted.flag("ok") || inserted.flag("notEmpty")
    }

    /** Gives a slow editor a moment to show what it took. */
    private suspend fun bodyFilledSoon(tab: DraftTab, editorUrl: String): Boolean =
        pollUntil(minOf(timing.imageWaitMs, PASTE_SETTLE_MS)) {
            true.takeUnless { bodyEmpty(tab, editorUrl) }
        } == true

    private fun logPaste(method: String, answer: JsonObject, dialogs: Int) {
        logInfo(
            "body method=$method ok=${answer.flag("ok")} accepted=${answer.flag("accepted")} " +
                "bodyChars=${answer["chars"]?.jsonPrimitive?.intOrNull ?: -1} dialogs=$dialogs",
        )
    }

    /**
     * Gives the body real focus before text goes in: the run's own window shows the tab, a click
     * lands in the body's last plain block when the page shows one, and a probe reports where the
     * focus ended up. Best effort: a failure here is logged and the paste goes ahead.
     */
    private suspend fun focusEditor(tab: DraftTab, editorUrl: String) {
        var front = false
        var clicked = false
        var why: String? = null
        try {
            stopIfOwnerHasChrome()
            if (drivesOwnWindow) {
                browser.bringToFront(tab.id)
                front = true
            }
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
                "focus front=$front clicked=$clicked target=${why ?: "ok"} " +
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
        if (!drivesOwnWindow && ownerHasChrome()) throw Halt("chrome-opened")
    }

    private fun stopIfMoved(answer: JsonObject) {
        if (answer.flag("moved")) throw Halt("editor-moved")
    }

    private fun sameHost(a: String, b: String): Boolean {
        val hostA = runCatching { java.net.URI(a).host }.getOrNull() ?: return false
        val hostB = runCatching { java.net.URI(b).host }.getOrNull() ?: return false
        return hostA.equals(hostB, ignoreCase = true)
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
        at?.let { put("at", it) }
    }

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TAG = "SaveDraftRoutine"
        const val WINDOW_TAB_GLANCE_MS = 5_000L
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

/** What [closeDraftWindow] did, for the log: ids and class names only. */
internal data class DraftWindowClosing(
    /** "none", "closed", or "failed-<class>". */
    val tab: String,
    /** Chrome tasks on the display at the end; null when unread or not looked at. */
    val onDisplay: Set<Int>?,
    val removed: List<Int>,
    val notRemoved: List<String>,
)

/**
 * Closes the run's own Chrome window before the virtual display goes: its tab over CDP, then the
 * window's tasks. A Chrome task on the display counts as the run's only if it did not exist,
 * on this display or the main one, before the window was opened ([preexisting]). Chrome may move
 * the window into a task other than the one first seen, so the comparison is made at the end.
 * The owner's tasks all predate the window and are never removed. Without [preexisting] nothing
 * is removed. Each part is best effort.
 */
internal suspend fun closeDraftWindow(
    tabId: String?,
    preexisting: Set<Int>?,
    closeTab: suspend (String) -> Unit,
    tasksOnDisplay: suspend () -> Set<Int>?,
    removeTask: suspend (Int) -> Boolean,
): DraftWindowClosing {
    val tab = if (tabId == null) {
        "none"
    } else {
        try {
            closeTab(tabId)
            "closed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The task removal below still takes the window away.
            "failed-" + e.javaClass.simpleName
        }
    }
    if (preexisting == null) return DraftWindowClosing(tab, null, emptyList(), emptyList())
    val onDisplay = try {
        tasksOnDisplay()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: return DraftWindowClosing(tab, null, emptyList(), listOf("tasks-unreadable"))
    val removed = mutableListOf<Int>()
    val notRemoved = mutableListOf<String>()
    for (id in onDisplay - preexisting) {
        val outcome = try {
            if (removeTask(id)) null else "refused"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
        if (outcome == null) removed += id else notRemoved += "$id:$outcome"
    }
    return DraftWindowClosing(tab, onDisplay, removed, notRemoved)
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
}

class DraftScriptException(script: DraftScript) : Exception("draft script failed: ${script.name}")

/** The script's return value, from a `Runtime.evaluate` response. */
internal fun draftEvaluateResult(script: DraftScript, response: JsonElement): JsonObject {
    val body = response.jsonObject
    if ("exceptionDetails" in body) throw DraftScriptException(script)
    val value = body["result"]?.jsonObject?.get("value") as? JsonObject
    return value ?: throw DraftScriptException(script)
}
