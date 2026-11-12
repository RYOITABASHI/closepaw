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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

    /** True once this run may have put the article on the clipboard; only then is it emptied. */
    @Volatile private var clipboardTouched = false

    /** False when the virtual display cannot be had. Never falls back to the main display. */
    suspend fun start(): Boolean {
        val platform = PlatformFactory.create(
            config = SessionConfig(platformMode = PlatformMode.VIRTUAL_DISPLAY, unattended = true),
            service = service,
            traceRecorder = NoopTraceRecorder,
        ) as? VirtualDisplayPlatform ?: return false
        platform.start()
        display = platform
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
     */
    suspend fun stop() {
        val done = withTimeoutOrNull(FINISH_TIMEOUT_MS) {
            finishDraftChrome(
                tabId = runTabId,
                closeTab = { close(it) },
                restore = { stage?.restore() },
                log = { Log.i(TAG, it) },
            )
        }
        if (done == null) Log.w(TAG, "draft chrome finish timed out")
        runTabId = null
        // The article may still be on the clipboard after a run that was cut short.
        try {
            clearClipboard()
        } catch (e: Exception) {
            Log.w(TAG, "clipboard not cleared: ${e.javaClass.simpleName}")
        }
        stage = null
        sessions.close()
        display?.stopKeepingTasks()
        display = null
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
        if (platform.showChrome() is ActionResult.Failure) throw IOException("chrome did not start")
    }

    override fun dialogsAnswered(): Int = dialogs.get()

    override fun runTab(tabId: String) {
        runTabId = tabId
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

    override suspend fun createTab(url: String): String {
        val response = cdp { client ->
            client.send("Target.createTarget", buildJsonObject { put("url", url) })
        }
        return response.jsonObject["targetId"]?.jsonPrimitive?.content
            ?: throw IOException("no target id")
    }

    override suspend fun open(url: String) {
        val platform = display ?: throw IOException("no virtual display")
        if (platform.openUrlInChrome(url) is ActionResult.Failure) throw IOException("chrome did not open")
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
        if (script == DraftScript.CLIPBOARD_WRITE) clipboardTouched = true
        val response = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
            cdp { client ->
                client.send("Runtime.evaluate", draftEvaluateParams(script, args), CdpOptions(targetId = tabId))
            }
        } ?: throw DraftEvaluateTimeoutException(script)
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
            val clip = ClipData.newHtmlText("draft", plain, html)
            if (Build.VERSION.SDK_INT >= 33) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboardTouched = true
            clipboard.setPrimaryClip(clip)
            Log.i(TAG, "draft clipboard set by ClosePaw")
            true
        } catch (e: Exception) {
            Log.w(TAG, "draft clipboard not set: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Empties the clipboard, if this run used it. The owner's earlier clip is not read back to restore it: reading the
     * clipboard from the background is refused, or announced on screen, on current Android.
     */
    override suspend fun clearClipboard() {
        if (!clipboardTouched) return
        clipboardTouched = false
        withContext(Dispatchers.Main) {
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return@withContext
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
        cdp { client ->
            client.send("Target.closeTarget", buildJsonObject { put("targetId", tabId) }, CdpOptions(targetId = tabId))
        }
        if (runTabId == tabId) runTabId = null
    }
}

class DraftEvaluateTimeoutException(script: DraftScript) : IOException("draft script timed out: ${script.name}")

private const val TAG = "ChromeDraftBrowser"
private const val EVALUATE_TIMEOUT_MS = 10_000L
private const val FINISH_TIMEOUT_MS = 20_000L

private const val FILE_CHOOSER_OPENED = "Page.fileChooserOpened"

/** CDP modifier bit for Ctrl. */
private const val CTRL = 2
