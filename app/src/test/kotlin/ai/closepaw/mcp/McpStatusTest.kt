package ai.closepaw.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpStatusTest {
    @Test
    fun reportsBindLimboAndIndependentPortObservations() {
        val status = status(McpHandler.AdbStatus(true, false, 37001, 123L, 37002, 456L, "nsd"))
        assertEquals(JsonPrimitive(true), status["virtualDisplayReady"])
        assertFalse(status.containsKey("shizukuReady"))
        assertEquals(JsonPrimitive(false), status["accessibilityReady"])
        assertEquals(JsonPrimitive(false), status["accessibilityBound"])
        assertEquals(JsonPrimitive(true), status["accessibilitySettingOn"])
        val recovery = status.getValue("recovery").jsonObject
        assertEquals(JsonPrimitive("unavailable"), recovery["adb"])
        assertEquals(JsonPrimitive(false), recovery["readLogsGranted"])
        val adb = status.getValue("adbChannel").jsonObject
        assertEquals(JsonPrimitive(37001), adb["lastPort"])
        assertEquals(adb["lastPort"], adb["lastSuccessfulPort"])
        assertEquals(JsonPrimitive(123L), adb["lastSuccessfulAt"])
        assertEquals(JsonPrimitive(37002), adb["discoveredPort"])
        assertEquals(JsonPrimitive(456L), adb["discoveredAt"])
        assertEquals(JsonPrimitive("nsd"), adb["discoverySource"])
    }

    @Test
    fun absentPortObservationsAreExplicitNulls() {
        val adb = status(McpHandler.AdbStatus(false, false, null)).getValue("adbChannel").jsonObject
        for (key in listOf("lastPort", "lastSuccessfulPort", "lastSuccessfulAt", "discoveredPort", "discoveredAt", "discoverySource")) {
            assertEquals(key, JsonNull, adb[key])
        }
    }

    private fun status(adb: McpHandler.AdbStatus) = with(
        McpHandler(
            expectedToken = { TOKEN },
            registry = McpRunRegistry(),
            backend = object : McpBackend {
                override fun startRun(goal: String) = error("Unexpected start")
                override fun stopRun(runHandle: String) = error("Unexpected stop")
                override fun status() = McpBackend.Status("test", true, false, accessibilitySettingOn = true)
            },
            audit = {},
            serverVersion = "test",
            adbStatus = { adb },
            recoveryStatus = { JsonObject(mapOf(
                "adb" to JsonPrimitive("unavailable"),
                "readLogsGranted" to JsonPrimitive(false),
            )) },
        ),
    ) {
        val headers = mapOf("authorization" to "Bearer $TOKEN")
        val init = handle(McpHttpRequest("POST", "/mcp", headers, """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))
        val session = init.headers.getValue(McpHandler.SESSION_HEADER)
        val response = handle(
            McpHttpRequest(
                "POST", "/mcp", headers + ("mcp-session-id" to session),
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"closepaw_status"}}""",
            ),
        )
        assertEquals(200, response.status)
        val result = Json.parseToJsonElement(requireNotNull(response.body)).jsonObject.getValue("result").jsonObject
        val text = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        Json.parseToJsonElement(text).jsonObject
    }
    private companion object {
        val TOKEN = "a".repeat(64)
    }
}
