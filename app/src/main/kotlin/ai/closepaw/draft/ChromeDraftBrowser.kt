package ai.closepaw.draft

import android.accessibilityservice.AccessibilityService
import ai.closepaw.browser.cdp.CdpOptions
import ai.closepaw.browser.script.BrowserSessionManager
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.PlatformFactory
import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.trace.NoopTraceRecorder
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [DraftBrowser] over the owner's Chrome: a Shizuku virtual display to keep Chrome visible, and
 * CDP through the same relay `browser_script` uses. The CDP calls below are the complete list
 * (SaveDraftScriptsTest pins it); input events are not among them.
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

    suspend fun stop() {
        sessions.close()
        display?.stop()
        display = null
    }

    override suspend fun open(url: String) {
        val platform = display ?: throw IOException("no virtual display")
        val opened = platform.openUrlInChrome(url)
        if (opened is ActionResult.Failure) throw IOException("chrome did not open")
    }

    override suspend fun tabs(): List<DraftTab> =
        sessions.listPageTargets().filter { it.type == "page" }.map { DraftTab(it.id, it.url) }

    override suspend fun navigate(tabId: String, url: String) {
        sessions.withCdpClient { client ->
            client.send("Page.navigate", buildJsonObject { put("url", url) }, CdpOptions(targetId = tabId))
        }
    }

    override suspend fun evaluate(tabId: String, script: DraftScript, args: JsonObject): JsonObject {
        val response = sessions.withCdpClient { client ->
            client.send("Runtime.evaluate", draftEvaluateParams(script, args), CdpOptions(targetId = tabId))
        }
        return draftEvaluateResult(script, response)
    }

    override suspend fun close(tabId: String) {
        sessions.withCdpClient { client ->
            client.send("Target.closeTarget", buildJsonObject { put("targetId", tabId) }, CdpOptions(targetId = tabId))
        }
    }
}
