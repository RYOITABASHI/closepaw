package ai.closepaw.app

import ai.closepaw.mcp.McpTypedOpError
import ai.closepaw.platform.DisplayInfo
import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import ai.closepaw.protocol.SessionState
import ai.closepaw.session.AgentSession
import ai.closepaw.session.SessionServices
import android.os.SystemClock
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class McpSessionGestureAdmissionTest {
    @Test
    fun priorProcessHandleDoesNotAdmitGestures() {
        val handle = "previous-session"
        val disk = mutableMapOf<String, String>()
        fun openStore() = RunRecoveryStore(
            disk, { key, value -> disk[key] = value; true }, { disk.remove(it) },
        )
        openStore().apply {
            begin(handle, correlation = null)
            markRunning(handle)
        }
        val sessions = AgentServiceBrainSessions(
            serviceProvider = { error("A stale handle must be refused before accessing the service") },
            backend = mockk(),
            runStore = openStore(),
        )
        val gestures = listOf(
            "tap_at" to """{"runHandle":"$handle","x":1,"y":2}""",
            "swipe" to """{"runHandle":"$handle","fromX":1,"fromY":2,"toX":3,"toY":4,"durationMs":300}""",
        )
        for ((name, arguments) in gestures) {
            val refusal = assertThrows(McpTypedOpError::class.java) {
                sessions.handle(name, Json.parseToJsonElement(arguments).jsonObject)
            }
            assertEquals("unknown_handle", refusal.code)
            assertEquals("No external session owns this handle", refusal.message)
        }
    }

    @Test
    fun swipeAdmitsLastValidPixelAtBothEndpoints() {
        val fixture = liveSession()
        val captureReached = IllegalStateException("Swipe reached pre-action capture")
        every { fixture.platform.getCurrentPackageName() } throws captureReached

        val outcome = assertThrows(IllegalStateException::class.java) { fixture.swipe() }

        assertSame(captureReached, outcome)
    }

    @Test
    fun swipeRefusesEachOutOfBoundsCoordinateBeforeDispatch() {
        val limits = mapOf("fromX" to WIDTH, "fromY" to HEIGHT, "toX" to WIDTH, "toY" to HEIGHT)
        for ((coordinate, limit) in limits) {
            for (value in listOf(limit, limit + 1)) {
                val fixture = liveSession()

                val refusal = assertThrows("$coordinate=$value", McpTypedOpError::class.java) {
                    fixture.swipe(mapOf(coordinate to value))
                }

                assertEquals("invalid_params", refusal.code)
                verify(exactly = 0) { fixture.services.toolRouter }
                coVerify(exactly = 0) { fixture.platform.performAction(any()) }
            }
        }
    }

    private fun liveSession(): LiveSession {
        val platform = mockk<VirtualDisplayPlatform>()
        every { platform.getDisplayInfo() } returns DisplayInfo(WIDTH, HEIGHT, 1f)
        every { platform.vdFailure() } returns null
        val services = mockk<SessionServices>()
        every { services.platform } returns platform
        every { services.traceRecorder } returns mockk()
        val session = mockk<AgentSession>()
        every { session.getServices() } returns services
        every { session.state } returns MutableStateFlow<SessionState>(SessionState.Idle)
        val owner = mockk<AgentService>()
        every { owner.isExternalSessionOwnerActive() } returns true
        val sessions = AgentServiceBrainSessions({ owner }, mockk(), mockk())

        // Seed a live handle without starting a real Android service or virtual display.
        val pending = mockk<ShellyPendingResult>()
        val managerClass = AgentServiceBrainSessions::class.java
        val startClass = managerClass.declaredClasses.single { it.simpleName == "BrainStart" }
        val start = startClass.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(owner, HANDLE, pending, null)
        val brainClass = managerClass.declaredClasses.single { it.simpleName == "BrainSession" }
        val brain = brainClass.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
            .newInstance(
                HANDLE, session, owner, "goal", AtomicBoolean(false), pending, null, start,
                SystemClock.elapsedRealtime(),
            )
        val sessionsField = managerClass.getDeclaredField("sessions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val liveHandles = sessionsField.get(sessions) as MutableMap<String, Any>
        liveHandles[HANDLE] = brain

        return LiveSession(sessions, platform, services)
    }

    private class LiveSession(
        val sessions: AgentServiceBrainSessions,
        val platform: VirtualDisplayPlatform,
        val services: SessionServices,
    ) {
        fun swipe(overrides: Map<String, Int> = emptyMap()) = sessions.handle("swipe", buildJsonObject {
            put("runHandle", HANDLE)
            put("fromX", overrides["fromX"] ?: WIDTH - 1)
            put("fromY", overrides["fromY"] ?: HEIGHT - 1)
            put("toX", overrides["toX"] ?: WIDTH - 1)
            put("toY", overrides["toY"] ?: HEIGHT - 1)
            put("durationMs", 300)
        })
    }

    private companion object {
        const val HANDLE = "live-session"
        const val WIDTH = 480
        const val HEIGHT = 800
    }
}
