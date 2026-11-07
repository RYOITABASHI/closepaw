package ai.closepaw.draft

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class DraftTab(val id: String, val url: String)

/** What the routine may do to Chrome. Scripts are a closed enum; URLs come from [DraftSite]. */
interface DraftBrowser {
    /** Opens [url] in a tab on the run's display. */
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
 * It stops rather than guesses. Nothing is written unless the page is on the site's editor URL
 * and the editor is empty. A step is never repeated after a write, so a timeout is `uncertain`:
 * the draft may exist, and only the owner or a read of the draft list can tell.
 */
class SaveDraftRoutine(
    private val browser: DraftBrowser,
    private val timing: SaveDraftTiming = SaveDraftTiming(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Read by [resultSoFar] after a cancellation, from another coroutine.
    @Volatile private var platform = DraftPlatform.NOTE
    @Volatile private var url: String? = null
    @Volatile private var wrote = false
    @Volatile private var titleOk = false
    @Volatile private var bodyOk = false
    @Volatile private var saved = false
    @Volatile private var dedupe = "not-checked"
    @Volatile private var imagesExpected = 0
    @Volatile private var imagesPlaced = 0
    @Volatile private var tabClosed = false

    suspend fun run(request: SaveDraftRequest): SaveDraftResult {
        platform = request.site.platform
        imagesExpected = request.images.size
        val finished = withTimeoutOrNull(timing.totalMs) {
            try {
                steps(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only: exception messages can carry page or request text.
                val reason = "error-" + e.javaClass.simpleName
                if (wrote) result(DraftOutcome.UNCERTAIN, reason) else result(DraftOutcome.FAILED, reason)
            }
        }
        return finished ?: result(DraftOutcome.UNCERTAIN, "timeout")
    }

    /** For a run stopped from outside: what is known so far, without touching the browser. */
    fun resultSoFar(reason: String): SaveDraftResult =
        result(if (wrote) DraftOutcome.UNCERTAIN else DraftOutcome.FAILED, reason)

    private fun result(outcome: DraftOutcome, reason: String?) = SaveDraftResult(
        outcome = outcome,
        platform = platform,
        url = url,
        reason = reason,
        wrote = wrote,
        titleOk = titleOk,
        bodyOk = bodyOk,
        saved = saved,
        dedupe = dedupe,
        imagesExpected = imagesExpected,
        imagesPlaced = imagesPlaced,
        tabClosed = tabClosed,
    )

    private suspend fun steps(request: SaveDraftRequest): SaveDraftResult {
        val site = request.site
        // Chrome may not be running yet; then every tab that shows up is one of ours or a
        // restored one, and the URL checks below still apply.
        val known = try {
            browser.tabs().map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

        browser.open(site.draftListUrl)
        // Only tabs this run opened are ever touched.
        val listTab = pollUntil(timing.tabWaitMs) {
            browser.tabs().firstOrNull { it.id !in known && site.isListUrl(it.url) }
        }
        if (listTab == null) {
            // Signed out, or the list moved. The editor check below decides what happens next.
            dedupe = "unknown"
        } else {
            val copy = findExisting(site, listTab, request.title)
            if (copy != null) {
                url = copy
                closeTab(listTab)
                return result(DraftOutcome.ALREADY_EXISTS, null)
            }
        }

        val tab = if (listTab != null) {
            browser.navigate(listTab.id, site.newDraftUrl)
            listTab
        } else {
            browser.open(site.newDraftUrl)
            pollUntil(timing.tabWaitMs) { browser.tabs().firstOrNull { it.id !in known && site.isEditorUrl(it.url) } }
                ?: return result(DraftOutcome.UNCERTAIN, "tab-not-found")
        }

        val editor = pollUntil(timing.editorWaitMs) {
            browser.evaluate(tab.id, DraftScript.EDITOR_PROBE, NO_ARGS).takeIf { probe ->
                site.isEditorUrl(probe.str("href").orEmpty()) && probe.flag("titleFound") && probe.flag("bodyFound")
            }
        } ?: return result(DraftOutcome.UNCERTAIN, "editor-not-reached")
        url = editor.str("href")
        if (!editor.flag("titleEmpty") || !editor.flag("bodyEmpty")) {
            return result(DraftOutcome.FAILED, "editor-not-empty")
        }

        wrote = true
        val titleSet = browser.evaluate(tab.id, DraftScript.SET_TITLE, titleArgs(request)).flag("ok")
        if (!titleSet) return result(DraftOutcome.UNCERTAIN, "title-not-set")

        // Images first: the caret is at the end of an empty body, so they lead the article.
        request.images.forEach { placeImage(tab, it) }

        val pasted = browser.evaluate(
            tab.id,
            DraftScript.PASTE_HTML,
            buildJsonObject { put("html", request.html) },
        ).flag("ok")
        if (!pasted) return result(DraftOutcome.UNCERTAIN, "body-not-pasted")

        val confirmation = saveAndConfirm(site, tab, request)
        // An unconfirmed editor stays open: closing it could discard what it still holds.
        if (confirmation.outcome != DraftOutcome.SAVED) return confirmation
        closeTab(tab)
        return confirmation.copy(tabClosed = tabClosed)
    }

    private suspend fun findExisting(site: DraftSite, tab: DraftTab, title: String): String? {
        val args = buildJsonObject { put("title", title) }
        var probe: JsonObject? = null
        val hit = pollUntil(timing.listWaitMs) {
            browser.evaluate(tab.id, DraftScript.LIST_PROBE, args).also { probe = it }.takeIf { it.flag("found") }
        }
        if (hit == null) {
            // Only the list's first screen is read, so this is "not seen", not "does not exist".
            dedupe = if (probe?.flag("ready") == true) "not-seen" else "unknown"
            return null
        }
        dedupe = "found"
        return hit.str("url")?.takeIf(site::isEditorUrl) ?: site.draftListUrl
    }

    private suspend fun placeImage(tab: DraftTab, image: DraftImage) {
        val args = buildJsonObject {
            put("name", image.name)
            put("mime", image.mime)
            put("base64", image.base64)
        }
        for (route in listOf(DraftScript.PASTE_IMAGE, DraftScript.FILE_INPUT_IMAGE)) {
            val before = imageCount(tab)
            if (!browser.evaluate(tab.id, route, args).flag("ok")) continue
            val landed = pollUntil(timing.imageWaitMs) {
                true.takeIf { imageCount(tab) > before }
            }
            if (landed == true) {
                imagesPlaced++
                return
            }
        }
    }

    private suspend fun imageCount(tab: DraftTab): Int =
        browser.evaluate(tab.id, DraftScript.IMAGE_COUNT, NO_ARGS)["count"]?.jsonPrimitive?.intOrNull ?: 0

    private suspend fun saveAndConfirm(site: DraftSite, tab: DraftTab, request: SaveDraftRequest): SaveDraftResult {
        val savedProbe: Pair<DraftScript, JsonObject>
        when (site.platform) {
            DraftPlatform.SUBSTACK -> {
                val id = url?.let(site::draftId) ?: return result(DraftOutcome.UNCERTAIN, "no-draft-id")
                savedProbe = DraftScript.SUBSTACK_SAVED_PROBE to contentArgs(request, "id" to JsonPrimitive(id))
            }
            DraftPlatform.NOTE -> {
                val click = browser.evaluate(tab.id, DraftScript.NOTE_SAVE_CLICK, NO_ARGS)
                if (!click.flag("clicked")) return result(DraftOutcome.UNCERTAIN, "save-control-not-unique")
                val at = click["at"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                savedProbe = DraftScript.NOTE_SAVED_PROBE to buildJsonObject { put("at", at) }
            }
        }

        val confirmed = pollUntil(timing.saveWaitMs) {
            val back = browser.evaluate(tab.id, DraftScript.READ_BACK, contentArgs(request))
            titleOk = back.flag("titleOk")
            bodyOk = back.flag("bodyOk")
            back.str("href")?.takeIf(site::isEditorUrl)?.let { url = it }
            if (titleOk && bodyOk) saved = saved || browser.evaluate(tab.id, savedProbe.first, savedProbe.second).flag("saved")
            true.takeIf { titleOk && bodyOk && saved }
        }
        return when {
            confirmed == true -> result(DraftOutcome.SAVED, null)
            !titleOk || !bodyOk -> result(DraftOutcome.UNCERTAIN, "read-back-mismatch")
            else -> result(DraftOutcome.UNCERTAIN, "save-unconfirmed")
        }
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
            } catch (e: Exception) {
                null
            }
            if (value != null) return value
            if (now() >= deadline) return null
            delay(timing.pollMs)
        }
    }

    private fun titleArgs(request: SaveDraftRequest) = buildJsonObject { put("title", request.title) }

    private fun contentArgs(request: SaveDraftRequest, vararg extra: Pair<String, JsonElement>) = buildJsonObject {
        put("title", request.title)
        put("samples", buildJsonArray { request.samples.forEach { add(JsonPrimitive(it)) } })
        extra.forEach { (key, value) -> put(key, value) }
    }

    private fun JsonObject.flag(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        val NO_ARGS = JsonObject(emptyMap())
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
