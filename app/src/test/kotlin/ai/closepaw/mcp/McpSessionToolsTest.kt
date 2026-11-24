package ai.closepaw.mcp

import ai.closepaw.app.ShellyBackgroundRun
import ai.closepaw.app.ShellyPendingResult
import ai.closepaw.app.ShellyResultCallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSessionToolsTest {
    @Test
    fun advertisesSessionToolsOnlyWithAnInjectedOperation() {
        val baseTools = setOf("run_goal", "get_run_result", "stop_run", "closepaw_status")
        val sessionTools = setOf("start_session", "observe", "tap", "type_text", "system_button",
            "open_app", "wait", "finish_run", "spike_inject_tap")
        assertEquals(baseTools, tools(handler()).keys)
        val enabled = tools(handler(sessionOps = { _, _ -> error("Unexpected dispatch") }))
        assertEquals(baseTools + sessionTools, enabled.keys)
        for (name in sessionTools) {
            val schema = enabled.getValue(name).getValue("inputSchema").jsonObject
            assertEquals(name, JsonPrimitive(false), schema["additionalProperties"])
        }
    }

    @Test
    fun spikeTapRequiresBothInjectionsToSucceedAndPreservesThePayload() {
        val audits = mutableListOf<String>()
        for (down in listOf(true, false, null)) for (up in listOf(true, false, null)) {
            val success = down == true && up == true
            val payload = buildJsonObject {
                put("reflectionOk", down != null)
                put("down", down)
                put("up", up)
                put("error", if (success) null else "Injection failed")
            }
            val handler = handler(audits = audits, sessionOps = { _, _ -> payload })
            val result = call(handler, session(handler), "spike_inject_tap", """{"runHandle":"valid","x":1,"y":2}""")
            assertEquals(JsonPrimitive(!success), result["isError"])
            assertEquals(payload, Json.parseToJsonElement(content(result)))
            val outcome = if (success) "ok" else "spike_failed"
            assertEquals("mcp tool=spike_inject_tap handle=valid outcome=$outcome", audits.last())
        }
    }

    @Test
    fun startSessionForwardsArgumentsAndAuditsWithoutLoggingGoal() {
        val audits = mutableListOf<String>()
        val goal = "Private goal canary"
        val arguments = buildJsonObject {
            put("goal", goal)
            put("reqId", REQ_ID)
            put("nonce", NONCE)
        }
        val payload = buildJsonObject { put("runHandle", "new-handle") }
        val handler = handler(audits = audits, sessionOps = { name, args ->
            assertEquals("start_session", name)
            assertEquals(arguments, args)
            payload
        })
        val headers = session(handler)
        val auditCount = audits.size
        val result = call(handler, headers, "start_session", arguments.toString())
        assertEquals(JsonPrimitive(false), result["isError"])
        assertEquals(payload, Json.parseToJsonElement(content(result)))
        assertEquals(auditCount + 1, audits.size)
        assertTrue(audits.last().contains("tool=start_session"))
        assertFalse(audits.last().contains(goal))
    }

    @Test
    fun invalidArgumentsReturnToolErrorsBeforeDispatch() {
        val audits = mutableListOf<String>()
        val handler = handler(audits = audits, sessionOps = { _, _ -> throw AssertionError("Unexpected dispatch") })
        val headers = session(handler)
        val invalid = listOf(
            "start_session" to "{}",
            "start_session" to """{"goal":"${"g".repeat(4001)}"}""",
            "observe" to "[]",
            "observe" to """{"runHandle":"valid","unexpected":true}""",
            "observe" to """{"runHandle":"valid","includeImage":"false"}""",
            "tap" to """{"runHandle":"valid","elementIndex":-1}""",
            "tap" to """{"runHandle":"valid","elementIndex":"1"}""",
            "tap" to """{"runHandle":"valid","elementIndex":true}""",
            "tap" to """{"runHandle":"valid","elementIndex":1.00000000000000001}""",
            "type_text" to """{"runHandle":"valid","text":"${"x".repeat(4001)}"}""",
            "system_button" to """{"runHandle":"valid","button":"home"}""",
            "open_app" to """{"runHandle":"valid","appName":"  "}""",
            "wait" to """{"runHandle":"valid","durationMs":30001}""",
        )
        for ((name, args) in invalid) {
            val auditCount = audits.size
            assertError("invalid_params", call(handler, headers, name, args))
            assertEquals(auditCount + 1, audits.size)
        }
        assertTrue(audits.contains("mcp tool=observe handle=valid outcome=invalid_params"))
    }

    @Test
    fun invalidStartsReachTheRefusalHookAndPreserveToolErrors() {
        val refusals = mutableListOf<Pair<JsonObject, String?>>()
        val pair = """"reqId":"1700000000-00000000","nonce":"0123456789abcdef0123456789abcdef""""
        val invalid = """{"goal":"  ",$pair}"""
        val baseline = handler(sessionOps = { _, _ -> error("Unexpected dispatch") })
        val hooked = handler(
            sessionOps = { _, _ -> error("Unexpected dispatch") },
            sessionStartRefusal = { args, message -> refusals.add(args to message) },
        )
        val errors = listOf(
            invalid to "goal must be a non-blank string",
            """{"goal":"  "}""" to "goal must be a non-blank string",
            """{"goal":false,"reqId":"invalid","nonce":"invalid"}""" to "goal must be a non-blank string",
            """{"goal":"Open notes","unexpected":true,$pair}""" to "unexpected argument",
        )
        val baselineHeaders = session(baseline)
        val headers = session(hooked)
        for ((args, _) in errors) {
            val expected = call(baseline, baselineHeaders, "start_session", args)
            assertError("invalid_params", expected)
            assertEquals(expected, call(hooked, headers, "start_session", args))
        }
        assertEquals(errors.map { (args, message) -> Json.parseToJsonElement(args).jsonObject to message }, refusals)

        val throwing = handler(
            sessionOps = { _, _ -> error("Unexpected dispatch") },
            sessionStartRefusal = { _, _ -> throw AssertionError("Refusal hook failed") },
        )
        assertEquals(
            call(baseline, baselineHeaders, "start_session", invalid),
            call(throwing, session(throwing), "start_session", invalid),
        )
    }

    @Test
    fun malformedStartsLeaveNoSessionOrCallbackAndAValidRetryBinds() {
        val fixture = AdmissionFixture()
        val malformed = listOf(
            """"reqId":"bad","nonce":"$NONCE"""",
            """"reqId":"$REQ_ID","nonce":"short"""",
            """"reqId":"$REQ_ID","nonce":"${"f".repeat(129)}"""",
            """"reqId":"$REQ_ID","nonce":"${"g".repeat(32)}"""",
            """"reqId":"$REQ_ID"""",
            """"nonce":"$NONCE"""",
        )
        for (pair in malformed) {
            assertError("invalid_params", fixture.call("start_session", """{"goal":"Open notes",$pair}"""))
            assertTrue(fixture.pending.isEmpty())
            assertEquals(0, fixture.external.activeCount())
            assertEquals(0, fixture.registry.activeCount())
            assertTrue(fixture.sentCallbacks.isEmpty())
            val handle = """{"runHandle":"$ADMISSION_HANDLE"}"""
            assertError("unknown_handle", fixture.call("get_run_result", handle))
            assertError("unknown_handle", fixture.call("stop_run", handle))
        }

        val started = payload(fixture.call("start_session", """{"goal":"Open notes",$VALID_PAIR}"""))
        assertEquals(JsonPrimitive(ADMISSION_HANDLE), started["runHandle"])
        assertEquals(
            ShellyBackgroundRun.ResultCorrelation(REQ_ID, NONCE),
            fixture.pending.getValue(ADMISSION_HANDLE).correlation,
        )
        assertTrue(fixture.sentCallbacks.isEmpty())
    }

    @Test
    fun absentPairStartsAnUnboundSession() {
        val fixture = AdmissionFixture()
        val started = payload(fixture.call("start_session", """{"goal":"Open notes"}"""))
        assertEquals(JsonPrimitive(ADMISSION_HANDLE), started["runHandle"])
        val pending = fixture.pending.getValue(ADMISSION_HANDLE)
        assertEquals(null, pending.correlation)
        val stopped = payload(fixture.call("stop_run", """{"runHandle":"$ADMISSION_HANDLE"}"""))
        assertEquals(JsonPrimitive(true), stopped["stopRequested"])
        assertTrue(pending.isClaimed)
        assertTrue(fixture.pending.isEmpty())
        assertTrue(fixture.sentCallbacks.isEmpty())
    }

    @Test
    fun validPairWithBlankGoalStillSendsARefusalCallback() {
        val fixture = AdmissionFixture()
        assertError("invalid_params", fixture.call("start_session", """{"goal":"  ",$VALID_PAIR}"""))
        assertTrue(fixture.pending.isEmpty())
        val callback = fixture.sentCallbacks.single()
        assertEquals(REQ_ID, callback.reqId)
        assertEquals(NONCE, callback.nonce)
        assertEquals(ShellyResultCallback.STATUS_FAILED, callback.status)
        assertEquals("goal must be a non-blank string", callback.summary)
    }

    @Test
    fun observesOnlyLiveExternalHandles() {
        val completed = "brain-completed"
        val running = "brain-running"
        val external = FakeExternalResults().apply {
            complete(completed, McpRunRegistry.Status.COMPLETED, "done")
            markRunning(running)
        }
        val payload = buildJsonObject { put("observed", true) }
        val handler = handler(sessionResults = external, sessionOps = { name, args ->
            assertEquals("observe", name)
            assertEquals(JsonPrimitive(running), args["runHandle"])
            payload
        })
        val headers = session(handler)
        assertError("unknown_handle", call(handler, headers, "observe", """{"runHandle":"unknown"}"""))
        val ended = call(handler, headers, "observe", """{"runHandle":"$completed"}""")
        assertError("session_ended", ended)
        assertTrue(content(ended).contains("session already completed"))
        val live = call(handler, headers, "observe", """{"runHandle":"$running"}""")
        assertEquals(JsonPrimitive(false), live["isError"])
        assertEquals(payload, Json.parseToJsonElement(content(live)))
    }

    @Test
    fun sessionToolsRejectRunGoalHandles() {
        val registry = McpRunRegistry()
        val completed = registry.issue()
        assertTrue(registry.complete(completed, McpRunRegistry.Status.COMPLETED, "done"))
        val running = registry.issue()
        assertTrue(registry.markRunning(running))
        val handler = handler(
            registry = registry,
            sessionResults = FakeExternalResults(),
            sessionOps = { _, _ -> error("unexpected dispatch") },
        )
        val headers = session(handler)

        for (handle in listOf(completed, running)) {
            assertError("unknown_handle", call(handler, headers, "observe", """{"runHandle":"$handle"}"""))
        }
    }

    @Test
    fun surfacesTypedAndGenericOperationErrors() {
        val handler = handler(sessionOps = { _, args ->
            if (args["goal"] == JsonPrimitive("typed")) {
                throw McpTypedOpError("session_not_found", "No driver session")
            }
            throw IllegalStateException("private exception details")
        })
        val headers = session(handler)
        assertError("session_not_found", call(handler, headers, "start_session", """{"goal":"typed"}"""))
        val generic = call(handler, headers, "start_session", """{"goal":"generic"}""")
        assertError("operation_failed", generic)
        assertEquals("Error: [operation_failed] start_session failed (IllegalStateException)", content(generic))
    }

    @Test
    fun stageCAccessorsPreserveTextAndApplyDefaultsAndNormalization() {
        fun validated(name: String, input: String): JsonObject {
            val args = Json.parseToJsonElement(input).jsonObject
            assertEquals(name, null, McpSessionArgs.validate(name, args))
            return args
        }

        val tap = validated("tap", """{"runHandle":"run-1","elementIndex":1.0}""")
        assertEquals("run-1", McpSessionArgs.runHandle(tap))
        assertEquals(1, McpSessionArgs.elementIndex(tap))
        val type = validated("type_text", """{"runHandle":"run-1","text":""}""")
        assertEquals("", McpSessionArgs.text(type))
        assertFalse(McpSessionArgs.clearFirst(type))
        val button = validated("system_button", """{"runHandle":"run-1","button":" BACK "}""")
        assertEquals("back", McpSessionArgs.button(button))
        val wait = validated("wait", """{"runHandle":"run-1","durationMs":3e4}""")
        assertEquals(30_000L, McpSessionArgs.durationMs(wait))
        assertEquals(1_000L, McpSessionArgs.durationMs(validated("wait", """{"runHandle":"run-1"}""")))
        assertFalse(McpSessionArgs.includeImage(validated("observe", """{"runHandle":"run-1"}""")))
    }

    @Test
    fun finishRunValidatesOutcomeAndDefaultsToDone() {
        val running = "brain-running"
        val external = FakeExternalResults().apply { markRunning(running) }
        var dispatched = 0
        val handler = handler(sessionResults = external, sessionOps = { name, args ->
            dispatched++
            assertEquals("finish_run", name)
            buildJsonObject { put("outcome", McpSessionArgs.outcome(args)) }
        })
        val headers = session(handler)
        val missingSummary = buildJsonObject { put("runHandle", running) }
        assertTrue(McpSessionArgs.validate("finish_run", missingSummary) != null)
        assertError("invalid_params", call(handler, headers, "finish_run", missingSummary.toString()))

        val baseArgs = buildJsonObject {
            put("runHandle", running)
            put("summary", "Test summary")
        }

        for (outcome in listOf(null, "done", "failed")) {
            val args = buildJsonObject {
                baseArgs.forEach { (key, value) -> put(key, value) }
                if (outcome != null) put("outcome", outcome)
            }
            assertEquals(null, McpSessionArgs.validate("finish_run", args))
            assertEquals(outcome ?: "done", McpSessionArgs.outcome(args))
            val result = call(handler, headers, "finish_run", args.toString())
            assertEquals(JsonPrimitive(false), result["isError"])
            assertEquals(JsonPrimitive(outcome ?: "done"), Json.parseToJsonElement(content(result)).jsonObject["outcome"])
        }

        for (invalid in listOf("\"DONE\"", "\"other\"", "null", "true", "1", "{}", "[]")) {
            val args = JsonObject(baseArgs + ("outcome" to Json.parseToJsonElement(invalid)))
            val error = assertThrows(McpTypedOpError::class.java) {
                McpSessionArgs.validate("finish_run", args)
            }
            assertEquals("invalid_params", error.code)
            assertError("invalid_params", call(handler, headers, "finish_run", args.toString()))
        }
        assertEquals(3, dispatched)
    }

    @Test
    fun finishRunRoutesCompletedExternalHandlesToTheSessionOperation() {
        val handle = "brain-completed"
        val external = FakeExternalResults().apply {
            complete(handle, McpRunRegistry.Status.CANCELLED, "Stopped")
        }
        val committed = buildJsonObject {
            put("runHandle", handle)
            put("status", "cancelled")
            put("verified", false)
        }
        val handler = handler(sessionResults = external, sessionOps = { name, args ->
            assertEquals("finish_run", name)
            assertEquals(JsonPrimitive(handle), args["runHandle"])
            committed
        })

        val result = call(
            handler, session(handler), "finish_run", """{"runHandle":"$handle","summary":"Retry"}""",
        )
        assertEquals(JsonPrimitive(false), result["isError"])
        assertEquals(committed, Json.parseToJsonElement(content(result)))
    }

    @Test
    fun getRunResultServesExternalHandles() {
        val registry = McpRunRegistry()
        val goalHandle = registry.issue()
        assertTrue(registry.complete(goalHandle, McpRunRegistry.Status.COMPLETED, "Goal summary"))
        val externalHandle = "brain-running"
        val external = FakeExternalResults().apply { markRunning(externalHandle) }
        val handler = handler(registry = registry, sessionResults = external)
        val headers = session(handler)

        fun getResult(handle: String) =
            call(handler, headers, "get_run_result", """{"runHandle":"$handle"}""")

        assertEquals(JsonPrimitive("running"), payload(getResult(externalHandle))["status"])
        external.complete(externalHandle, McpRunRegistry.Status.COMPLETED, "Brain summary")
        val completed = payload(getResult(externalHandle))
        assertEquals(JsonPrimitive("completed"), completed["status"])
        assertEquals(JsonPrimitive("Brain summary"), completed["summary"])
        assertError("unknown_handle", getResult("unknown"))
        assertEquals(JsonPrimitive("Goal summary"), payload(getResult(goalHandle))["summary"])
    }

    @Test
    fun stopRunConsultsExternalResults() {
        val handle = "brain-running"
        val external = FakeExternalResults().apply { markRunning(handle) }
        val audits = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        val handler = handler(audits = audits, sessionResults = external, stopRun = {
            stopped.add(it)
            external.complete(it, McpRunRegistry.Status.CANCELLED, "Stopped")
            true
        })
        val headers = session(handler)
        val args = """{"runHandle":"$handle"}"""

        val requested = payload(call(handler, headers, "stop_run", args))
        assertEquals(JsonPrimitive(true), requested["stopRequested"])
        assertEquals(JsonPrimitive("cancelled"), requested["status"])
        assertEquals("mcp tool=stop_run handle=$handle outcome=stop_requested", audits.last())

        val ended = payload(call(handler, headers, "stop_run", args))
        assertEquals(JsonPrimitive(false), ended["stopRequested"])
        assertEquals(JsonPrimitive("cancelled"), ended["status"])
        assertEquals("mcp tool=stop_run handle=$handle outcome=already_cancelled", audits.last())
        assertEquals(listOf(handle), stopped)
    }

    @Test
    fun legacyRunHandleAuditsUsePrintableAscii() {
        val audits = mutableListOf<String>()
        val handler = handler(audits = audits)
        val headers = session(handler)
        val args = buildJsonObject { put("runHandle", "unknown\nλhandle") }

        for (name in listOf("get_run_result", "stop_run")) {
            assertError("unknown_handle", call(handler, headers, name, args.toString()))
            assertEquals("mcp tool=$name handle=unknownhandle outcome=unknown_handle", audits.last())
        }
    }

    @Test
    fun finishRunSchemaAdvertisesOptionalOutcomeEnum() {
        val enabled = tools(handler(sessionOps = { _, _ -> error("Unexpected dispatch") }))
        val schema = enabled.getValue("finish_run").getValue("inputSchema").jsonObject
        val outcome = schema.getValue("properties").jsonObject.getValue("outcome").jsonObject

        assertEquals(JsonPrimitive("string"), outcome["type"])
        assertEquals(listOf(JsonPrimitive("done"), JsonPrimitive("failed")), outcome.getValue("enum").jsonArray)
        assertEquals(listOf(JsonPrimitive("runHandle"), JsonPrimitive("summary")), schema.getValue("required").jsonArray)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
    }

    private fun tools(handler: McpHandler): Map<String, JsonObject> =
        result(handler.handle(request("tools/list", session(handler)))).getValue("tools").jsonArray
            .map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }

    private fun call(handler: McpHandler, headers: Map<String, String>, name: String, args: String) =
        result(handler.handle(request("tools/call", headers, ""","params":{"name":"$name","arguments":$args}""")))

    private fun assertError(code: String, result: JsonObject) {
        assertEquals(JsonPrimitive(true), result["isError"])
        assertTrue(content(result), content(result).startsWith("Error: [$code] "))
    }

    private fun payload(result: JsonObject): JsonObject {
        assertEquals(JsonPrimitive(false), result["isError"])
        return Json.parseToJsonElement(content(result)).jsonObject
    }

    private fun content(result: JsonObject): String {
        val item = result.getValue("content").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("text"), item["type"])
        return item.getValue("text").jsonPrimitive.content
    }

    private fun handler(
        registry: McpRunRegistry = McpRunRegistry(),
        audits: MutableList<String> = mutableListOf(),
        sessionOps: ((String, JsonObject) -> JsonObject)? = null,
        sessionResults: McpExternalResults? = null,
        stopRun: (String) -> Boolean = { error("Unexpected stop") },
        sessionStartRefusal: ((JsonObject, String?) -> Unit)? = null,
    ) = McpHandler(
        expectedToken = { TOKEN },
        registry = registry,
        backend = object : McpBackend {
            override fun startRun(goal: String) = error("Unexpected start")
            override fun stopRun(runHandle: String) = stopRun.invoke(runHandle)
            override fun status() = error("Unexpected status")
        },
        audit = { audits.add(it) },
        serverVersion = "test",
        sessionOps = sessionOps,
        sessionResults = sessionResults,
        sessionStartRefusal = sessionStartRefusal,
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

    // The standalone JVM rig injects session operations and records payloads at the send boundary.
    private inner class AdmissionFixture {
        val registry = McpRunRegistry()
        val external = FakeExternalResults()
        val pending = mutableMapOf<String, ShellyPendingResult>()
        val sentCallbacks = mutableListOf<ShellyResultCallback.Payload>()
        private val admissionHandler = handler(
            registry = registry,
            sessionResults = external,
            sessionOps = { name, args ->
                assertEquals("start_session", name)
                pending[ADMISSION_HANDLE] = ShellyPendingResult(correlation(args))
                external.markRunning(ADMISSION_HANDLE)
                buildJsonObject { put("runHandle", ADMISSION_HANDLE) }
            },
            stopRun = { handle ->
                val result = pending.remove(handle) ?: error("Unexpected stop")
                result.claimCancelled(0L)?.let(sentCallbacks::add)
                external.complete(handle, McpRunRegistry.Status.CANCELLED, "Stopped")
                true
            },
            sessionStartRefusal = { args, message ->
                correlation(args)?.let { correlation ->
                    ShellyPendingResult(correlation).claimFailed(message, 0L)?.let(sentCallbacks::add)
                }
            },
        )
        private val headers = session(admissionHandler)

        fun call(name: String, args: String) = call(admissionHandler, headers, name, args)

        private fun correlation(args: JsonObject) =
            ShellyBackgroundRun.parseResultCorrelation(McpSessionArgs.reqId(args), McpSessionArgs.nonce(args))
    }

    private class FakeExternalResults : McpExternalResults {
        private val snapshots = mutableMapOf<String, McpRunRegistry.Snapshot>()

        fun complete(handle: String, status: McpRunRegistry.Status, summary: String) {
            snapshots[handle] = McpRunRegistry.Snapshot(handle, status, summary)
        }

        fun markRunning(handle: String) {
            snapshots[handle] = McpRunRegistry.Snapshot(handle, McpRunRegistry.Status.RUNNING, "")
        }

        override fun snapshot(runHandle: String) = snapshots[runHandle]

        override fun await(runHandle: String, waitMs: Long) = snapshot(runHandle)

        override fun activeCount() = snapshots.values.count { !it.status.terminal }
    }

    private companion object {
        const val ADMISSION_HANDLE = "brain-admission"
        const val REQ_ID = "1700000000-0123abcd"
        const val NONCE = "0123456789abcdef0123456789abcdef"
        const val VALID_PAIR = """"reqId":"$REQ_ID","nonce":"$NONCE""""
        val TOKEN = "a".repeat(64)
    }
}
