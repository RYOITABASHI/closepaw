package ai.closepaw.draft

import android.accessibilityservice.AccessibilityService
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
