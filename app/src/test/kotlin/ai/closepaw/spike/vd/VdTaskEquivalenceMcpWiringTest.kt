package ai.closepaw.spike.vd

import ai.closepaw.mcp.McpBackend
import ai.closepaw.mcp.McpHandler
import ai.closepaw.mcp.McpHttpRequest
import ai.closepaw.mcp.McpRunRegistry
import ai.closepaw.mcp.McpVdTaskEquivalence
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VdTaskEquivalenceMcpWiringTest {
    private class Fake : McpVdTaskEquivalence {
        var opens = 0
        var closes = 0
        val readCalls = mutableListOf<Pair<Int, Int>>()

        override fun open() = buildJsonObject {
            put("accepted", ++opens == 1)
            put("reason", "opened")
            put("vdDisplayId", 42)
        }

        override fun pairedReads(displayId: Int, reads: Int): JsonObject {
            readCalls += displayId to reads
            return buildJsonObject {
                put("accepted", true)
                put("lines", JsonArray(List(reads) { JsonPrimitive("display=$displayId diffs=NONE") }))
                put("totalReads", readCalls.sumOf { it.second })
            }
        }

        override fun close() = buildJsonObject {
            put("accepted", true)
            put("closes", ++closes)
        }

        override fun status() = buildJsonObject {
            put("sessionOpen", opens > closes)
            put("totalReads", readCalls.sumOf { it.second })
        }
    }

    private fun handler(fake: McpVdTaskEquivalence?): Pair<McpHandler, Map<String, String>> {
        val token = "c".repeat(64)
        val handler = McpHandler(
            expectedToken = { token },
            registry = McpRunRegistry(),
            backend = object : McpBackend {
                override fun startRun(goal: String): McpBackend.StartResult = error("Unexpected run")
                override fun stopRun(runHandle: String) = false
                override fun status() = McpBackend.Status("test", false, true)
            },
            audit = {},
            serverVersion = "test",
            vdTaskEquivalence = fake,
        )
        val auth = mapOf("authorization" to "Bearer $token")
        val init = handler.handle(McpHttpRequest(
            "POST", "/mcp", auth, """{"jsonrpc":"2.0","id":1,"method":"initialize"}""",
        ))
        assertEquals(200, init.status)
        return handler to (auth + ("mcp-session-id" to init.headers.getValue("Mcp-Session-Id")))
    }

    private fun request(handler: McpHandler, headers: Map<String, String>, method: String, params: String = "{}"): JsonObject {
        val response = handler.handle(McpHttpRequest(
            "POST", "/mcp", headers,
            """{"jsonrpc":"2.0","id":2,"method":"$method","params":$params}""",
        ))
        assertEquals(200, response.status)
        return Json.parseToJsonElement(response.body!!).jsonObject
    }

    private fun call(handler: McpHandler, headers: Map<String, String>, name: String, arguments: String): JsonObject =
        request(handler, headers, "tools/call", """{"name":"$name","arguments":$arguments}""")

    private fun content(reply: JsonObject): JsonObject {
        val text = reply.getValue("result").jsonObject.getValue("content").jsonArray.single()
            .jsonObject.getValue("text").jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun isError(reply: JsonObject): Boolean =
        reply.getValue("result").jsonObject["isError"] == JsonPrimitive(true)

    @Test fun toolIsAbsentWhenNotWired() {
        val (handler, headers) = handler(null)
        val tools = request(handler, headers, "tools/list").getValue("result").jsonObject
            .getValue("tools").jsonArray
        assertFalse(tools.any { it.jsonObject["name"] == JsonPrimitive("spike_vd_task_equivalence") })
        val call = call(handler, headers, "spike_vd_task_equivalence", """{"action":"open"}""")
        assertEquals(JsonPrimitive(-32602), call.getValue("error").jsonObject["code"])
    }

    @Test fun listsDispatchesAndReportsStatusThroughAuthenticatedSession() {
        val fake = Fake()
        val (handler, headers) = handler(fake)
        val tools = request(handler, headers, "tools/list").getValue("result").jsonObject
            .getValue("tools").jsonArray
        assertTrue(tools.any { it.jsonObject["name"] == JsonPrimitive("spike_vd_task_equivalence") })

        // Malformed argument shapes are rejected before dispatch.
        for (invalid in listOf(
            """[]""",
            """{"action":1}""",
            """{"action":"bogus"}""",
            """{"action":"open","unexpected":true}""",
            """{"action":"read","displayId":"0"}""",
            """{"action":"read","displayId":-1}""",
            """{"action":"read","displayId":0,"reads":0}""",
            """{"action":"read","displayId":0,"reads":51}""",
            """{"action":"read","displayId":0,"reads":"3"}""",
        )) {
            val rejected = call(handler, headers, "spike_vd_task_equivalence", invalid)
            assertEquals("arguments $invalid", JsonPrimitive(-32602), rejected.getValue("error").jsonObject["code"])
        }
        assertEquals(0, fake.opens)
        assertTrue(fake.readCalls.isEmpty())

        // Semantic errors surface as tool errors, not RPC errors, and never dispatch.
        assertTrue(isError(call(handler, headers, "spike_vd_task_equivalence", """{}""")))
        assertTrue(isError(call(handler, headers, "spike_vd_task_equivalence", """{"action":"read"}""")))
        assertTrue(fake.readCalls.isEmpty())

        val opened = content(call(handler, headers, "spike_vd_task_equivalence", """{"action":"open"}"""))
        assertEquals(JsonPrimitive(true), opened["accepted"])
        assertEquals(JsonPrimitive(42), opened["vdDisplayId"])
        assertEquals(1, fake.opens)

        val one = content(call(handler, headers, "spike_vd_task_equivalence", """{"action":"read","displayId":7}"""))
        assertEquals(1, one.getValue("lines").jsonArray.size)
        assertEquals(listOf(7 to 1), fake.readCalls)

        val three = content(call(handler, headers, "spike_vd_task_equivalence", """{"action":"read","displayId":0,"reads":3}"""))
        assertEquals(3, three.getValue("lines").jsonArray.size)
        assertEquals(listOf(7 to 1, 0 to 3), fake.readCalls)

        val status = content(call(handler, headers, "closepaw_status", """{}"""))
        val spike = status.getValue("spikeVdTasks").jsonObject
        assertEquals(JsonPrimitive(true), spike["sessionOpen"])
        assertEquals(JsonPrimitive(4), spike["totalReads"])

        val closed = content(call(handler, headers, "spike_vd_task_equivalence", """{"action":"close"}"""))
        assertEquals(JsonPrimitive(true), closed["accepted"])
        assertEquals(1, fake.closes)
    }
}
