package ai.closepaw.session

import ai.closepaw.history.HistoryManager
import ai.closepaw.browser.cdp.CdpConnection
import ai.closepaw.browser.cdp.CdpConnectionClosedException
import ai.closepaw.browser.cdp.CdpConnectionFactory
import ai.closepaw.browser.cdp.shizuku.DevtoolsVersion
import ai.closepaw.browser.cdp.shizuku.PageTarget
import ai.closepaw.browser.script.BrowserDevtoolsBridge
import ai.closepaw.browser.script.BrowserScriptExecutor
import ai.closepaw.browser.script.BrowserSessionManager
import ai.closepaw.browser.script.ScriptResult
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.trace.NoopTraceRecorder
import ai.closepaw.trace.TraceRecorder
import ai.closepaw.test.buildTestContext
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class SessionServicesCleanupTest {

    @Test
    fun `cleanup returns Success when all steps succeed`() = runBlocking {
        val trace = mockk<TraceRecorder>(relaxed = true)
        val result = buildServices(trace).cleanup()
        check(result is CleanupResult.Success)
        coVerify { trace.close() }
    }

    @Test
    fun `cleanup closes browser session manager`() = runBlocking {
        val bridge = FakeBrowserBridge()
        val connectionFactory = FakeCdpConnectionFactory()
        val context = buildTestContext(java.io.File("/tmp/session-browser-cleanup")).also {
            every { it.applicationContext } returns it
        }
        val scope = CoroutineScope(SupervisorJob())
        val manager = BrowserSessionManager(
            context = context,
            sessionScope = scope,
            traceRecorder = NoopTraceRecorder,
            bridgeFactory = { bridge },
            cdpConnectionFactory = connectionFactory,
            runnerFactory = { _, _, _ ->
                BrowserScriptExecutor { _, _ -> ScriptResult.Ok("\"ok\"") }
            },
        )
        val runResult = manager.run("return 1", 1_000)
        check(runResult is ScriptResult.Ok) { "expected fake browser_script success, got $runResult" }

        val services = buildServices(
            trace = mockk(relaxed = true),
            browserSessionManager = manager
        )

        services.cleanup()

        assert(bridge.closeCalls == 1) { "expected browser bridge close once, got ${bridge.closeCalls}" }
        assert(connectionFactory.connections.single().closeCalls == 1) {
            "expected CDP connection close once, got ${connectionFactory.connections.single().closeCalls}"
        }
    }

    private fun buildServices(
        trace: TraceRecorder,
        browserSessionManager: BrowserSessionManager? = null
    ): SessionServices {
        val toolRegistry = ToolRegistry()
        val policyEngine = PolicyEngine(appClassifier = AppClassifier(emptyMap()))
        val toolRouter = ToolRouter(toolRegistry, policyEngine)
        return SessionServices(
            toolRegistry = toolRegistry,
            toolRouter = toolRouter,
            historyManager = HistoryManager(),
            sessionState = AgentSessionState(),
            policyEngine = policyEngine,
            appClassifier = AppClassifier(emptyMap()),
            platform = FakeAndroidPlatform(),
            config = SessionConfig(actionDelayMs = 0),
            traceRecorder = trace,
            recordingService = mockk(relaxed = true),
            browserSessionManager = browserSessionManager
        )
    }

    private class FakeBrowserBridge : BrowserDevtoolsBridge {
        var closeCalls = 0
            private set

        override suspend fun preflight() = Unit

        override suspend fun fetchVersion(): DevtoolsVersion =
            DevtoolsVersion(
                browser = "Chrome/130",
                protocolVersion = "1.3",
                webSocketDebuggerUrl = "ws://test",
                userAgent = null,
            )

        override suspend fun listPageTargets(): List<PageTarget> = emptyList()

        override fun close() {
            closeCalls++
        }
    }

    private class FakeCdpConnectionFactory : CdpConnectionFactory {
        val connections = mutableListOf<FakeCdpConnection>()

        override suspend fun connect(
            url: String,
            onMessage: (String) -> Unit,
            onFailure: (Throwable) -> Unit,
            onClosed: (CdpConnectionClosedException) -> Unit,
        ): CdpConnection {
            val connection = FakeCdpConnection(onMessage)
            connections.add(connection)
            return connection
        }
    }

    private class FakeCdpConnection(
        private val onMessage: (String) -> Unit,
    ) : CdpConnection {
        var closeCalls = 0
            private set

        override fun send(text: String) {
            val request = Json.parseToJsonElement(text).jsonObject
            val id = request["id"]!!.jsonPrimitive.int
            val method = request["method"]!!.jsonPrimitive.content
            val result = when (method) {
                "Target.createTarget" -> buildJsonObject { put("targetId", "blank-page") }
                "Target.attachToTarget" -> buildJsonObject { put("sessionId", "session-1") }
                else -> buildJsonObject { }
            }
            onMessage(
                buildJsonObject {
                    put("id", id)
                    put("result", result)
                }.toString()
            )
        }

        override fun close() {
            closeCalls++
        }
    }
}
