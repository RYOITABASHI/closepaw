package ai.closepaw.draft

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.text.Html
import android.util.Log
import ai.closepaw.browser.cdp.CdpOptions
import ai.closepaw.browser.cdp.ChromeCdpClient
import ai.closepaw.browser.script.BrowserSessionManager
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.PlatformFactory
import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.trace.NoopTraceRecorder
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [DraftBrowser] over the owner's Chrome: a Shizuku virtual display to keep Chrome visible, and
 * CDP through the same relay `browser_script` uses. The CDP calls below are the complete list
 * (SaveDraftScriptsTest pins it). The one input event is a left click, at a point a fixed script
 * has just shown to be inside a plain block of the editor body. JavaScript dialogs are answered
 * by the CDP client itself, by [draftDialogAccept]: an open dialog freezes the page, and every
 * `Runtime.evaluate` behind it would wait out its timeout.
 */
internal class ChromeDraftBrowser(
    private val service: AccessibilityService,
    scope: CoroutineScope,
) : DraftBrowser {
    private val sessions = BrowserSessionManager(
        context = service.applicationContext,
        sessionScope = scope,
        traceRecorder = NoopTraceRecorder,
    )
    private var display: VirtualDisplayPlatform? = null
    private val dialogs = AtomicInteger(0)

    /** The run's own tab, closed on every exit; and the stage that moved the owner's Chrome. */
    @Volatile private var runTabId: String? = null
    @Volatile private var stage: DraftChromeStage? = null

    /**
     * Fragments of tabs this run asked Chrome for and has not identified by id yet: recorded BEFORE
     * Chrome receives the request, on both creation paths ([createTab] and the activity-launch
     * fallback, [DraftBrowser.launchMarker]). A run cut short between the request and its answer
     * still finds and closes its tab on exit by these (gate fix round 2, finding 6).
     */
    private val pendingMarkers = CopyOnWriteArraySet<String>()

    /** The run's successful clipboard writes; only a provably own clip is emptied ([DraftClipOwnership]). */
    private val clip = DraftClipOwnership()

    /** This run's label for the clip [setClipboard] writes: identifies it as the run's. */
    private val clipLabel = "closepaw-draft-" + java.util.UUID.randomUUID().toString()

    /** False when the virtual display cannot be had. Never falls back to the main display. */
    suspend fun start(): Boolean {
        val platform = PlatformFactory.create(
            // isolateFocus: build 61's background focus policy (API 34+ OWN_FOCUS +
            // STEAL_TOP_FOCUS_DISABLED), so launching Chrome here cannot take the focus or the IME
            // from the owner's display 0. Not preserveMainDisplayTasks: the draft's Chrome task
            // moves are DraftChromeStage's, and the restorer must not move them too.
            config = SessionConfig(platformMode = PlatformMode.VIRTUAL_DISPLAY, unattended = true, isolateFocus = true),
            service = service,
            traceRecorder = NoopTraceRecorder,
        ) as? VirtualDisplayPlatform ?: return false
        display = platform
        platform.start()
        return true
    }

    /**
     * Ends the run on every exit, in this order: the run's tab is closed, the owner's Chrome goes
     * back to the main display with whatever they had in front put back in front
     * ([DraftChromeStage.restore]), then the display is released.
     *
     * The display is released without removing its tasks: if putting Chrome back failed, Android
     * moves what is left to the main display behind what is in front there, rather than closing
     * the owner's Chrome.
     *
     * Non-cancellable, and every step is independent (gate fix round 1, finding 3): the tab close
     * and the restore have their own budgets ([finishDraftChrome]), and the release runs whatever
     * happened before it. Never throws.
     */
    suspend fun stop(): Unit = withContext(NonCancellable) {
        try {
            finishDraftChrome(
                tabId = runTabId,
                closeTab = { close(it) },
                // The marker search is a separate step with its own budget, so a stalled close of
                // the named tab cannot use up its time (gate fix round 4, finding 1).
                recoverTabs = if (pendingMarkers.isEmpty()) null else ({ recoverMarkerTabs() }),
                restore = { stage?.restore() },
                log = { Log.i(TAG, it) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "draft chrome finish failed: ${e.javaClass.simpleName}")
        }
        runTabId = null
        pendingMarkers.clear()
        // The article may still be on the clipboard after a run that was cut short.
        try {
            clearClipboard()
        } catch (e: Exception) {
            Log.w(TAG, "clipboard not cleared: ${e.javaClass.simpleName}")
        }
        stage = null
        try {
            sessions.close()
        } catch (e: Exception) {
            Log.w(TAG, "CDP sessions not closed: ${e.javaClass.simpleName}")
        }
        try {
            display?.stopKeepingTasks()
        } catch (e: Exception) {
            Log.w(TAG, "draft display release failed: ${e.javaClass.simpleName}")
        }
        display = null
    }

    /**
     * Closes every tab still carrying an unresolved pending marker (`#` fragment). Needed beside the
     * named tab's close (gate fix round 3, finding 3): when `Target.createTarget` opened a tab but
     * its answer was lost, the activity fallback's tab becomes [runTabId] while the first tab is
     * known only by its marker. Runs as its own [finishDraftChrome] step with its own budget, after
     * the named close and never sharing its time (gate fix round 4, finding 1).
     *
     * Each marker is tracked on its own: it is dropped only once a tab carrying it was found and
     * closed. A tab requested just before a cut may not be listed yet, so the search keeps polling
     * while any marker is unresolved, until the step's budget ends it (round 4, finding 1). A
     * failed close leaves that marker unresolved and is retried on the next poll. Returns once
     * every marker is resolved; otherwise the step's budget cancels it (logged as timed-out).
     */
    private suspend fun recoverMarkerTabs() {
        val unresolved = pendingMarkers.toMutableSet()
        while (unresolved.isNotEmpty()) {
            val listed = try {
                tabs()
            } catch (e: IOException) {
                emptyList()
            }
            for (tab in listed) {
                val carried = unresolved.filter { tab.url.contains(it) }
                if (carried.isEmpty()) continue
                val closed = try {
                    close(tab.id)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (closed) {
                    unresolved.removeAll(carried.toSet())
                    pendingMarkers.removeAll(carried.toSet())
                }
            }
            if (unresolved.isEmpty()) break
            delay(MARKER_POLL_MS)
        }
    }

    override suspend fun prepareChrome(): String? {
        val platform = display ?: return "virtual-display-unavailable"
        val control = object : DraftTaskControl {
            override suspend fun mainDisplayTasks(): List<DraftRootTask>? =
                platform.mainDisplayTasks()?.let { tasks ->
                    // Front first: the system lists tasks front to back; a hidden one cannot be
                    // what the owner is looking at.
                    val (shown, hidden) = tasks.partition { it.visible != false }
                    (shown + hidden).map { DraftRootTask(it.id, ChromeFlagDeepLink.CHROME_PACKAGE in it.packages) }
                }

            override suspend fun chromeTasksOnRunDisplay(): Set<Int>? = platform.chromeTaskIds()

            override suspend fun moveToRunDisplay(taskId: Int): Boolean = platform.moveTaskHere(taskId)

            override suspend fun moveToMainDisplay(taskId: Int): Boolean = platform.moveTaskToMainDisplay(taskId)

            override suspend fun moveToFront(taskId: Int): Boolean = platform.moveTaskToFront(taskId)

            override suspend fun remove(taskId: Int): Boolean = platform.removeTask(taskId)
        }
        val newStage = DraftChromeStage(control) { Log.i(TAG, it) }
        stage = newStage
        return newStage.prepare()
    }

    override suspend fun showChrome() {
        val platform = display ?: throw IOException("no virtual display")
        requireChromeLaunch(platform.showChrome())
    }

    override fun dialogsAnswered(): Int = dialogs.get()

    /**
     * Records the run's tab. Does NOT clear the pending markers (gate fix round 3, finding 3): a
     * marker whose tab was never identified (a lost `Target.createTarget` answer) stays pending, so
     * teardown still searches for it. Each marker is resolved only by its own tab: [createTab] on
     * Chrome's answer, [markerResolved] when the routine found the fallback's tab by it.
     */
    override fun runTab(tabId: String) {
        runTabId = tabId
    }

    override fun launchMarker(marker: String) {
        pendingMarkers += marker
    }

    override fun markerResolved(marker: String) {
        pendingMarkers -= marker
    }

    /** The session's CDP client, with this run's dialog answer installed (it may be rebuilt). */
    private suspend fun <T> cdp(block: suspend (ChromeCdpClient) -> T): T =
        sessions.withCdpClient { client ->
            client.dialogAnswer = { state ->
                dialogs.incrementAndGet()
                // The type only: the message is page text.
                Log.i(TAG, "draft dialog answered: type=${state.type}")
                draftDialogAccept(state.type)
            }
            block(client)
        }

    /**
     * The tab's identity exists before Chrome receives the request (gate fix round 2, finding 6):
     * the URL carries a fresh random fragment, recorded first. Should the answer never arrive
     * (cancellation, a dropped connection), the exit path finds the tab by that fragment. The
     * fragment never leaves the device: browsers do not send it to the server.
     */
    override suspend fun createTab(url: String): String {
        val marker = "#cpd-" + java.util.UUID.randomUUID().toString().replace("-", "")
        pendingMarkers += marker
        val response = cdp { client ->
            client.send("Target.createTarget", buildJsonObject { put("url", url + marker) })
        }
        val id = response.jsonObject["targetId"]?.jsonPrimitive?.content
            ?: throw IOException("no target id")
        // Owned from the moment Chrome names it, not only once the routine records it: a run cut
        // short in between still closes this tab on exit.
        runTabId = id
        // Only this request's marker is resolved; any other stays pending (round 3, finding 3).
        pendingMarkers -= marker
        return id
    }

    override suspend fun open(url: String) {
        val platform = display ?: throw IOException("no virtual display")
        requireChromeLaunch(platform.openUrlInChrome(url))
    }

    private fun requireChromeLaunch(result: ActionResult) {
        when (result) {
            is ActionResult.Success -> Unit
            is ActionResult.Failure -> throw DraftChromeLaunchException(result.reason)
            is ActionResult.Cancelled -> throw CancellationException(result.reason)
        }
    }

    override suspend fun tabs(): List<DraftTab> =
        sessions.listPageTargets().filter { it.type == "page" }.map { DraftTab(it.id, it.url) }

    override suspend fun navigate(tabId: String, url: String) {
        cdp { client ->
            client.send("Page.navigate", buildJsonObject { put("url", url) }, CdpOptions(targetId = tabId))
        }
    }

    /**
     * Capped well under the client's 30 s, so a page that stalls ends the step sooner. The cap
     * gives up as an [IOException], not a cancellation, so the routine counts it as a miss.
     */
    override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
        val response = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                client.send("Runtime.evaluate", draftEvaluateParams(script, args), CdpOptions(targetId = tabId))
            }
        } ?: throw DraftEvaluateTimeoutException(script)
        // The page's own clipboard write (CLIPBOARD_WRITE) is deliberately NOT recorded for
        // cleanup (gate fix round 3, finding 1): nothing read back can tell it from an identical
        // owner copy, so it is never cleared. It is what the page itself put there.
        return draftEvaluateResult(script, response)
    }

    override suspend fun bringToFront(tabId: String) {
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client -> client.send("Page.bringToFront", JsonObject(emptyMap()), CdpOptions(targetId = tabId)) }
        } ?: throw IOException("bringToFront timed out")
    }

    /**
     * The fallback when the page cannot write the clipboard: ClosePaw puts the HTML there itself.
     * Marked sensitive, so the system's clipboard preview does not show the text.
     */
    override suspend fun setClipboard(html: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val clipboard = service.getSystemService(ClipboardManager::class.java)
                ?: return@withContext false
            val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            val data = ClipData.newHtmlText(clipLabel, plain, html)
            if (Build.VERSION.SDK_INT >= 33) {
                data.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(data)
            // Recorded only after the set returned (round 1 claimed ownership before it), with the
            // exact text written: cleanup compares it unnormalized (gate fix round 3, finding 1).
            clip.recordWrite(clipLabel, html, plain)
            Log.i(TAG, "draft clipboard set by ClosePaw")
            true
        } catch (e: Exception) {
            Log.w(TAG, "draft clipboard not set: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Empties the clipboard only when the clip on it now is provably ClosePaw's own write: read
     * back as a single item under this run's unique label with exactly the content written
     * ([DraftClipOwnership], gate fix rounds 2 and 3, finding 1). The page's own write is never
     * cleared. A clip that cannot be read, or is anyone else's, is left alone. The owner's earlier
     * clip is not restored: reading
     * the clipboard from the background is refused, or announced on screen, on current Android.
     * Runs to the end even when the run was cancelled.
     */
    override suspend fun clearClipboard() {
        withContext(NonCancellable + Dispatchers.Main) {
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return@withContext
            val current = try {
                clipboard.primaryClip
            } catch (e: Exception) {
                null
            }
            val itemCount = current?.itemCount ?: 0
            // Only a single-item clip can be fully inspected; releaseForClear refuses any other.
            val item = current?.takeIf { itemCount == 1 }?.getItemAt(0)
            val mine = clip.releaseForClear(
                label = current?.description?.label?.toString(),
                itemCount = itemCount,
                text = item?.text?.toString(),
                htmlText = item?.htmlText,
            )
            if (!mine) {
                if (current == null) Log.i(TAG, "draft clipboard not readable; left as is")
                return@withContext
            }
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            Log.i(TAG, "draft clipboard cleared")
        }
    }

    override suspend fun interceptFileChooser(tabId: String, enabled: Boolean) {
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                client.send("Page.setInterceptFileChooserDialog", buildJsonObject { put("enabled", enabled) }, CdpOptions(targetId = tabId))
                // Requests from before this run's press are not this run's.
                if (enabled) client.drainEvents()
            }
        } ?: throw IOException("file chooser interception timed out")
    }

    override suspend fun takeFileChooser(tabId: String): Long? = cdp { client ->
        client.drainEvents().lastOrNull { it.method == FILE_CHOOSER_OPENED }
            ?.params?.get("backendNodeId")?.jsonPrimitive?.longOrNull
    }

    override suspend fun fileToChooser(tabId: String, backendNodeId: Long, args: JsonObject): JsonObject {
        val response = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                val node = client.send("DOM.resolveNode", buildJsonObject { put("backendNodeId", backendNodeId) }, CdpOptions(targetId = tabId))
                val objectId = node.jsonObject["object"]?.jsonObject?.get("objectId")?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("file input not resolved")
                client.send("Runtime.callFunctionOn", draftCallParams(DraftScript.FILE_TO_INPUT, objectId, args), CdpOptions(targetId = tabId))
            }
        } ?: throw DraftEvaluateTimeoutException(DraftScript.FILE_TO_INPUT)
        return draftEvaluateResult(DraftScript.FILE_TO_INPUT, response)
    }

    /** Ctrl+V with the browser's own paste command: a trusted paste from the clipboard. */
    override suspend fun pasteKey(tabId: String) {
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                for (type in listOf("rawKeyDown", "keyUp")) {
                    val event = buildJsonObject {
                        put("type", type)
                        put("modifiers", CTRL)
                        put("key", "v")
                        put("code", "KeyV")
                        put("windowsVirtualKeyCode", 86)
                        if (type == "rawKeyDown") put("commands", buildJsonArray { add(JsonPrimitive("paste")) })
                    }
                    client.send("Input.dispatchKeyEvent", event, CdpOptions(targetId = tabId))
                }
            }
        } ?: throw IOException("paste key timed out")
    }

    override val typesText: Boolean get() = true

    /** Trusted text at the focus, like an IME commit: the page sees real input events. */
    override suspend fun insertText(tabId: String, text: String) {
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                client.send("Input.insertText", buildJsonObject { put("text", text) }, CdpOptions(targetId = tabId))
            }
        } ?: throw IOException("insert text timed out")
    }

    /**
     * One trusted key from [DraftKey]: Backspace deletes selected anchor text, Enter makes a
     * paragraph after a selected image.
     */
    override suspend fun pressKey(tabId: String, key: DraftKey) {
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                for (type in listOf("rawKeyDown", "keyUp")) {
                    val event = buildJsonObject {
                        put("type", type)
                        put("key", key.key)
                        put("code", key.key)
                        put("windowsVirtualKeyCode", key.keyCode)
                    }
                    client.send("Input.dispatchKeyEvent", event, CdpOptions(targetId = tabId))
                }
            }
        } ?: throw IOException("key timed out")
    }

    override suspend fun hover(tabId: String, x: Double, y: Double) {
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                val event = buildJsonObject {
                    put("type", "mouseMoved")
                    put("x", x)
                    put("y", y)
                }
                client.send("Input.dispatchMouseEvent", event, CdpOptions(targetId = tabId))
            }
        } ?: throw IOException("hover timed out")
    }

    override suspend fun click(tabId: String, x: Double, y: Double) {
        withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                for (type in listOf("mousePressed", "mouseReleased")) {
                    val event = buildJsonObject {
                        put("type", type)
                        put("x", x)
                        put("y", y)
                        put("button", "left")
                        put("clickCount", 1)
                    }
                    client.send("Input.dispatchMouseEvent", event, CdpOptions(targetId = tabId))
                }
            }
        } ?: throw IOException("click timed out")
    }

    override suspend fun close(tabId: String) {
        val response = cdp { client ->
            client.send("Target.closeTarget", buildJsonObject { put("targetId", tabId) }, CdpOptions(targetId = tabId))
        }
        // Older Chrome answers {success}; a false one means the tab is still open, so it stays
        // the run's and the exit path tries again. Newer Chrome answers {} on success.
        val success = (response as? JsonObject)?.get("success")?.jsonPrimitive?.booleanOrNull
        if (success == false) throw IOException("chrome refused to close the tab")
        if (runTabId == tabId) runTabId = null
    }
}

class DraftEvaluateTimeoutException(script: DraftScript) : IOException("draft script timed out: ${script.name}")

private const val TAG = "ChromeDraftBrowser"
private const val EVALUATE_TIMEOUT_MS = 10_000L

/** How often the exit path looks again for tabs known only by their markers ([DRAFT_RECOVER_BUDGET_MS]). */
private const val MARKER_POLL_MS = 250L

private const val FILE_CHOOSER_OPENED = "Page.fileChooserOpened"

/** CDP modifier bit for Ctrl. */
private const val CTRL = 2

/** Chrome launch failures carry only controller-generated diagnostics. */
internal class DraftChromeLaunchException(val reason: String) : IOException(reason)
