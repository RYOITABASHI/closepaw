package ai.closepaw.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTypedOpsTest {
    @Test
    fun advertisesSchemasAndUsesAuthenticatedTextResult() {
        val audits = mutableListOf<String>()
        val handler = handler(audits, typedOps = { name, _ ->
            assertEquals("capture_screen", name)
            buildJsonObject {
                put("mime", "image/jpeg")
                put("width", 100)
                put("height", 80)
                put("base64", "test")
            }
        })
        val params = ""","params":{"name":"capture_screen"}"""
        assertEquals(401, handler.handle(request("tools/call", emptyMap(), params)).status)
        val headers = session(handler)
        val tools = result(handler.handle(request("tools/list", headers))).getValue("tools").jsonArray
            .associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
        for (name in listOf("secure_setting", "read_logcat", "capture_screen", "get_ui_tree")) {
            val schema = tools.getValue(name).jsonObject.getValue("inputSchema").jsonObject
            assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        }
        val secure = tools.getValue("secure_setting").jsonObject.getValue("inputSchema").jsonObject
        val key = secure.getValue("properties").jsonObject.getValue("key").jsonObject
        assertEquals(listOf("enabled_accessibility_services", "accessibility_enabled", "private_dns_mode", "private_dns_specifier"),
            key.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        val variants = secure.getValue("anyOf").jsonArray.map { it.jsonObject }.groupBy { variant ->
            variant.getValue("properties").jsonObject.getValue("action").jsonObject
                .getValue("enum").jsonArray.single().jsonPrimitive.content
        }
        assertEquals(setOf("put", "get"), variants.keys)
        val puts = variants.getValue("put")
        assertEquals(2, puts.size)
        for (put in puts) assertEquals(setOf("action", "key", "value"), required(put))
        val putProperties = puts.map { it.getValue("properties").jsonObject }
        val accessibilityPut = putProperties.single { "value" in it }
        assertEquals(listOf(JsonPrimitive("accessibility_enabled")),
            accessibilityPut.getValue("key").jsonObject.getValue("enum").jsonArray.toList())
        assertEquals(listOf(JsonPrimitive("0"), JsonPrimitive("1")),
            accessibilityPut.getValue("value").jsonObject.getValue("enum").jsonArray.toList())
        val generalPut = putProperties.single { "value" !in it }
        assertEquals(setOf(JsonPrimitive("enabled_accessibility_services"), JsonPrimitive("private_dns_mode"),
            JsonPrimitive("private_dns_specifier")),
            generalPut.getValue("key").jsonObject.getValue("enum").jsonArray.toSet())
        val get = variants.getValue("get").single()
        assertEquals(setOf("action", "key"), required(get))
        assertEquals(setOf("value"), required(get.getValue("not").jsonObject))

        val logcat = tools.getValue("read_logcat").jsonObject.getValue("inputSchema").jsonObject
            .getValue("properties").jsonObject
        assertEquals(JsonPrimitive(1000), logcat.getValue("lines").jsonObject["maximum"])
        val filter = logcat.getValue("filter").jsonObject
        assertEquals(JsonPrimitive(512), filter["maxLength"])
        val filterPattern = Regex(filter.getValue("pattern").jsonPrimitive.content)
        assertTrue(filterPattern.matches("tag.*"))
        for (invalid in listOf("", "-tag", "\u0000tag", "tag\u007F", "tag\u009F")) {
            assertTrue(!filterPattern.matches(invalid))
        }
        val packageName = logcat.getValue("packageName").jsonObject
        assertEquals(JsonPrimitive(255), packageName["maxLength"])
        val packagePattern = Regex(packageName.getValue("pattern").jsonPrimitive.content)
        assertTrue(packagePattern.matches("ai.closepaw"))
        assertTrue(!packagePattern.matches("-ai.closepaw"))

        val capture = result(handler.handle(request("tools/call", headers, params)))
        assertEquals(JsonPrimitive(false), capture["isError"])
        val content = capture.getValue("content").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("text"), content["type"])
        val data = Json.parseToJsonElement(content.getValue("text").jsonPrimitive.content).jsonObject
        assertEquals(JsonPrimitive("image/jpeg"), data["mime"])
        assertTrue(audits.any { it.contains("tool=capture_screen") && it.contains("outcome=ok") })
    }

    @Test
    fun reportsUnavailableAdbAsAToolError() {
        val handler = handler(mutableListOf(), selfUpdate = object : McpSelfUpdate {
            override fun start(connectPort: Int?) = buildJsonObject { put("error", "adb_channel_unavailable") }
            override fun status() = error("Unexpected status")
        })
        val response = handler.handle(request("tools/call", session(handler), ""","params":{"name":"self_update"}"""))
        val update = result(response)
        assertEquals(JsonPrimitive(true), update["isError"])
        val error = update.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(error.contains("[adb_channel_unavailable]"))
    }

    private fun required(schema: JsonObject): Set<String> =
        schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun handler(
        audits: MutableList<String>,
        selfUpdate: McpSelfUpdate? = null,
        typedOps: ((String, JsonObject) -> JsonObject)? = null,
    ) = McpHandler(
        expectedToken = { TOKEN },
        registry = McpRunRegistry(),
        backend = object : McpBackend {
            override fun stopRun(runHandle: String) = error("Unexpected stop")
            override fun status() = error("Unexpected status")
        },
        audit = { audits.add(it) },
        serverVersion = "test",
        selfUpdate = selfUpdate,
        typedOps = typedOps,
    )

    private fun session(handler: McpHandler): Map<String, String> {
        val headers = mapOf("authorization" to "Bearer $TOKEN")
        val init = handler.handle(request("initialize", headers))
        return headers + ("mcp-session-id" to init.headers.getValue(McpHandler.SESSION_HEADER))
    }

    private fun request(method: String, headers: Map<String, String>, params: String = "") =
        McpHttpRequest("POST", "/mcp", headers, """{"jsonrpc":"2.0","id":1,"method":"$method"$params}""")

    private fun result(response: McpHttpResponse) =
        Json.parseToJsonElement(requireNotNull(response.body)).jsonObject.getValue("result").jsonObject

    private companion object {
        val TOKEN = "a".repeat(64)
    }
}
