package ai.closepaw.draft

import android.accessibilityservice.AccessibilityService
import android.util.Log
import ai.closepaw.browser.cdp.CdpOptions
import ai.closepaw.browser.cdp.ChromeCdpClient
import ai.closepaw.browser.script.BrowserSessionManager
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.PlatformFactory
import ai.closepaw.platform.virtualdisplay.VirtualDisplayAppController
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

    /**
     * Every Chrome task that existed before [openOwnWindow] put a window on the display (null when
     * there is no own window or the lists were unreadable), and the run's tab in that window.
     */
    @Volatile private var preexistingTasks: Set<Int>? = null
    @Volatile private var ownWindow = false
    @Volatile private var ownTabId: String? = null

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
     * Releases the display without removing its tasks. `am start --display` can move the owner's
     * existing Chrome task here, so anything on this display may be theirs, with tabs and unsaved
     * editors in it. Android then moves the task back to the main display, behind what is in front
     * there, because the display is not created to destroy its content. A tab this run left open
     * therefore stays open in Chrome.
     *
     * The run's own window is the exception: it is closed first, on every exit, so it never moves
     * to the owner's screen. Only the tasks [openOwnWindow] saw appear are removed.
     */
    suspend fun stop() {
        if (ownWindow) {
            val closing = withTimeoutOrNull(WINDOW_CLOSE_TIMEOUT_MS) {
                closeDraftWindow(
                    tabId = ownTabId,
                    preexisting = preexistingTasks,
                    closeTab = { close(it) },
                    tasksOnDisplay = { display?.chromeTaskIds() },
                    removeTask = { display?.removeTask(it) == true },
                )
            }
            Log.i(TAG, "draft chrome window closing: ${closing ?: "timed out"} preexisting=${preexistingTasks != null}")
            ownWindow = false
            preexistingTasks = null
            ownTabId = null
        }
        sessions.close()
        display?.stopKeepingTasks()
        display = null
    }

    override suspend fun showChrome() {
        val platform = display ?: throw IOException("no virtual display")
        if (platform.showChrome() is ActionResult.Failure) throw IOException("chrome did not start")
    }

    override suspend fun openOwnWindow(url: String): DraftWindow {
        val platform = display ?: return DraftWindow.NONE
        val launched = platform.openChromeWindow(url)
        ownWindow = launched.launch == VirtualDisplayAppController.ChromeWindowLaunch.ON_DISPLAY
        preexistingTasks = launched.preexistingTaskIds.takeIf { ownWindow }
        val window = when (launched.launch) {
            VirtualDisplayAppController.ChromeWindowLaunch.ON_DISPLAY -> DraftWindow.VIRTUAL_DISPLAY
            VirtualDisplayAppController.ChromeWindowLaunch.ELSEWHERE -> DraftWindow.ELSEWHERE
            VirtualDisplayAppController.ChromeWindowLaunch.FAILED -> DraftWindow.NONE
        }
        Log.i(TAG, "draft chrome window: $window")
        return window
    }

    override fun dialogsAnswered(): Int = dialogs.get()

    override fun ownWindowTab(tabId: String) {
        ownTabId = tabId
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
        if (ownTabId == tabId) ownTabId = null
    }
}

class DraftEvaluateTimeoutException(script: DraftScript) : IOException("draft script timed out: ${script.name}")

private const val TAG = "ChromeDraftBrowser"
private const val EVALUATE_TIMEOUT_MS = 10_000L
private const val WINDOW_CLOSE_TIMEOUT_MS = 8_000L
