package ai.closepaw.draft

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
                // Class name only: exception messages can carry page or request text.
                resultSoFar("error-" + e.javaClass.simpleName)
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
        browser.showChrome()
        pollUntil(timing.tabWaitMs) { browser.tabs() }
            ?: return result(DraftOutcome.UNCERTAIN, "chrome-not-reachable")

        val tab = openOwnTab(site.draftListUrl)
            ?: return result(DraftOutcome.UNCERTAIN, "tab-not-found")

        val copy = findExisting(tab)
        if (copy != null) {
            url = copy
            closeTab(tab)
            return result(DraftOutcome.ALREADY_EXISTS, null)
        }

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

        val titleSet = write(tab, editorUrl, DraftScript.SET_TITLE) { put("title", request.title) }
        if (!titleSet.flag("ok")) return result(DraftOutcome.UNCERTAIN, "title-not-set")

        // Images first: the caret is at the end of an empty body, so they lead the article.
        request.images.forEach { placeImage(tab, editorUrl, it) }

        val pasted = write(tab, editorUrl, DraftScript.PASTE_HTML) { put("html", request.html) }
        if (!pasted.flag("ok")) return result(DraftOutcome.UNCERTAIN, "body-not-pasted")

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
        return pollUntil(timing.tabWaitMs) { browser.tabs().firstOrNull { it.url.endsWith(marker) } }
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

    private suspend fun placeImage(tab: DraftTab, editorUrl: String, image: DraftImage) {
        for (route in listOf(DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE)) {
            val before = imageCount(tab)
            val answer = write(tab, editorUrl, route) {
                put("name", image.name)
                put("mime", image.mime)
                put("base64", image.base64)
            }
            if (!answer.flag("ok")) continue
            val landed = pollUntil(timing.imageWaitMs) { true.takeIf { imageCount(tab) > before } }
            if (landed == true) {
                imagesPlaced++
                return
            }
        }
    }

    private suspend fun imageCount(tab: DraftTab): Int =
        browser.evaluate(tab.id, DraftScript.IMAGE_COUNT, NO_ARGS)["count"]?.jsonPrimitive?.intOrNull ?: 0

    private suspend fun saveAndConfirm(tab: DraftTab, editorUrl: String): SaveDraftResult {
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
        at?.let { put("at", it) }
    }

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        val NO_ARGS = JsonObject(emptyMap())
        val RANDOM = SecureRandom()

        fun randomMarker(): String {
            val bytes = ByteArray(16).also(RANDOM::nextBytes)
            return "closepaw-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

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
