package ai.closepaw.mcp

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One HTTP request as the transport hands it over. Header names are lowercase. */
data class McpHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

/** [body] null = no body (202 / DELETE). Always JSON when present. */
data class McpHttpResponse(
    val status: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * What the MCP surface may ask of ClosePaw. Deliberately tiny and typed: there is NO raw
 * tap / swipe / text / shell entry; [startRun] (a natural-language goal) is the only way to make
 * ClosePaw act, and it goes through the same background-run machinery as Shelly hand-offs.
 */
interface McpBackend {
    sealed interface StartResult {
        data class Started(val runHandle: String) : StartResult
        data class Refused(val code: String, val message: String) : StartResult
    }

    data class Status(
        val version: String,
        val virtualDisplayReady: Boolean,
        val accessibilityReady: Boolean,
        val accessibilitySettingOn: Boolean = false,
    )

    /** Blocking; called on an HTTP worker thread. */
    fun startRun(goal: String): StartResult

    /** Asks the run behind [runHandle] to stop. False if it is not (or no longer) stoppable. */
    fun stopRun(runHandle: String): Boolean

    fun status(): Status
}

interface McpExternalResults {
    fun snapshot(runHandle: String): McpRunRegistry.Snapshot?
    fun await(runHandle: String, waitMs: Long): McpRunRegistry.Snapshot?
    fun activeCount(): Int
}

/**
 * MCP Streamable HTTP endpoint logic (POST /mcp, JSON responses, no SSE), protocol 2025-06-18
 * with the `initialize` + `Mcp-Session-Id` session, modelled on Shelly's legacy-era handler
 * (scripts/shelly-mcp-server.js `handle` / `handleLegacy`): same check order (Origin -> path ->
 * bearer -> method), same status codes and JSON-RPC error codes.
 *
 * Transport-agnostic and pure JVM so the whole protocol is unit tested; [McpHttpServer] only
 * moves bytes. [audit] receives one line per call (never the token, never the goal text).
 */
class McpHandler(
    private val expectedToken: () -> String?,
    private val registry: McpRunRegistry,
    private val backend: McpBackend,
    private val audit: (String) -> Unit,
    private val serverVersion: String,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val adbStatus: () -> AdbStatus = { AdbStatus(false, false, null) },
    private val selfUpdate: McpSelfUpdate? = null,
    private val typedOps: ((String, JsonObject) -> JsonObject)? = null,
    private val sessionOps: ((String, JsonObject) -> JsonObject)? = null,
    private val sessionResults: McpExternalResults? = null,
    private val sessionStartRefusal: ((JsonObject, String?) -> Unit)? = null,
) {
    data class AdbStatus(
        val paired: Boolean,
        val connected: Boolean,
        val lastPort: Int?,
        val lastSuccessfulAt: Long? = null,
        val discoveredPort: Int? = null,
        val discoveredAt: Long? = null,
        val discoverySource: String? = null,
    )

    private val tools = JsonArray(buildList {
        addAll(TOOLS)
        if (selfUpdate != null) add(SELF_UPDATE_TOOL)
        if (typedOps != null) addAll(TYPED_OP_TOOLS)
        if (sessionOps != null) addAll(SESSION_OP_TOOLS)
    })
    private val toolNames = buildSet {
        addAll(TOOL_NAMES)
        if (selfUpdate != null) add(TOOL_SELF_UPDATE)
        if (typedOps != null) addAll(TYPED_OP_NAMES)
        if (sessionOps != null) addAll(SESSION_OP_NAMES)
    }
    private val sessionsLock = Any()

    /** Live session ids, access-ordered so the eldest is evicted first. */
    private val sessions = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_SESSIONS
    }

    /** Drops every session (e.g. after the token was regenerated). */
    fun clearSessions() = synchronized(sessionsLock) { sessions.clear() }

    fun handle(req: McpHttpRequest): McpHttpResponse {
        if (!McpAuth.isOriginAllowed(req.headers["origin"])) {
            audit("mcp rejected: forbidden origin")
            return jsonResponse(403, buildJsonObject { put("error", "forbidden origin") })
        }
        if (req.path != PATH) return jsonResponse(404, buildJsonObject { put("error", "not found") })
        if (!McpAuth.isAuthorized(req.headers["authorization"], expectedToken())) {
            audit("mcp rejected: unauthorized")
            return jsonResponse(401, buildJsonObject { put("error", "unauthorized") })
        }
        if (req.method == "DELETE") {
            val sid = req.headers["mcp-session-id"]
            val removed = sid != null && synchronized(sessionsLock) { sessions.remove(sid) != null }
            if (removed) return McpHttpResponse(200, null)
            return methodNotAllowed()
        }
        // No standalone SSE stream (GET), same as Shelly.
        if (req.method != "POST") return methodNotAllowed()

        val parsed = try {
            JSON.parseToJsonElement(req.body)
        } catch (e: Exception) {
            return jsonResponse(400, rpcError(JsonNull, PARSE_ERROR, "parse error"))
        }
        val msg = parsed as? JsonObject
        val method = (msg?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (msg == null || (msg["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0" || method == null) {
            return jsonResponse(400, rpcError(msg?.get("id") ?: JsonNull, INVALID_REQUEST, "invalid JSON-RPC request"))
        }
        return try {
            dispatch(msg, method, req.headers)
        } catch (e: Exception) {
            audit("mcp method=$method outcome=internal_error(${e.javaClass.simpleName})")
            jsonResponse(500, rpcError(msg["id"] ?: JsonNull, INTERNAL_ERROR, "internal error"))
        }
    }

    private fun dispatch(msg: JsonObject, method: String, headers: Map<String, String>): McpHttpResponse {
        val id = msg["id"] ?: JsonNull
        val isNotification = !msg.containsKey("id")
        val params = msg["params"] as? JsonObject

        if (method == "initialize") {
            val requested = (params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
            val negotiated = if (requested in SUPPORTED_VERSIONS) requested!! else PROTOCOL_VERSION
            val sid = newSessionId()
            synchronized(sessionsLock) { sessions[sid] = true }
            audit("mcp method=initialize outcome=session_created version=$negotiated")
            return jsonResponse(
                200,
                rpcResult(id, buildJsonObject {
                    put("protocolVersion", negotiated)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", SERVER_NAME)
                        put("version", serverVersion)
                    }
                    put("instructions", INSTRUCTIONS)
                }),
                mapOf(SESSION_HEADER to sid),
            )
        }

        val sid = headers["mcp-session-id"]
        val known = sid != null && synchronized(sessionsLock) { sessions[sid] != null }
        if (!known) {
            return jsonResponse(
                400,
                rpcError(id, SESSION_ERROR, "missing or unknown Mcp-Session-Id — call initialize first"),
            )
        }
        // notifications/initialized, notifications/cancelled, …: accepted with no body.
        if (isNotification) return McpHttpResponse(202, null)

        return when (method) {
            "ping" -> jsonResponse(200, rpcResult(id, JsonObject(emptyMap())))
            "tools/list" -> jsonResponse(200, rpcResult(id, buildJsonObject { put("tools", tools) }))
            "tools/call" -> {
                val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
                if (name == null || name !in toolNames) {
                    audit("mcp tool=${sanitize(name)} handle=- outcome=unknown_tool")
                    return jsonResponse(200, rpcError(id, INVALID_PARAMS, "unknown tool: ${sanitize(name)}"))
                }
                val arguments = params?.get("arguments")
                if (name == TOOL_SELF_UPDATE && arguments != null && arguments !is JsonObject) {
                    audit("mcp tool=$name handle=- outcome=invalid_params")
                    return jsonResponse(200, rpcError(id, INVALID_PARAMS, "self_update arguments must be an object"))
                }
                if ((name in TYPED_OP_NAMES || name in SESSION_OP_NAMES) &&
                    arguments != null && arguments !is JsonObject
                ) {
                    audit("mcp tool=$name handle=- outcome=invalid_params")
                    return jsonResponse(200, rpcResult(
                        id, toolError(null, "invalid_params", "$name arguments must be an object", "invalid_params").result,
                    ))
                }
                val args = arguments as? JsonObject ?: JsonObject(emptyMap())
                jsonResponse(200, rpcResult(id, callTool(name, args)))
            }
            else -> jsonResponse(200, rpcError(id, METHOD_NOT_FOUND, "method not found: ${sanitize(method)}"))
        }
    }

    // ── Tools ──

    private class ToolOutcome(val handle: String?, val outcome: String, val result: JsonObject)

    private fun callTool(name: String, args: JsonObject): JsonObject {
        val out = when (name) {
            TOOL_RUN_GOAL -> runGoal(args)
            TOOL_GET_RUN_RESULT -> getRunResult(args)
            TOOL_STOP_RUN -> stopRun(args)
            TOOL_STATUS -> status()
            TOOL_SELF_UPDATE -> selfUpdate(args)
            in TYPED_OP_NAMES -> typedOp(name, args)
            in SESSION_OP_NAMES -> sessionOp(name, args)
            else -> error("unreachable")
        }
        val auditHandle = out.handle?.let(::sanitize)
        audit("mcp tool=$name handle=${auditHandle ?: "-"} outcome=${out.outcome}")
        return out.result
    }

    private fun typedOp(name: String, args: JsonObject): ToolOutcome = try {
        ToolOutcome(null, "ok", toolOk(checkNotNull(typedOps)(name, args)))
    } catch (e: McpTypedOpError) {
        toolError(null, e.code, e.message, e.code)
    } catch (e: Exception) {
        toolError(null, "operation_failed", "$name failed (${e.javaClass.simpleName})", "operation_failed")
    }

    private fun sessionOp(name: String, args: JsonObject): ToolOutcome {
        val validationError = try {
            McpSessionArgs.validate(name, args)
        } catch (e: McpTypedOpError) {
            return toolError(handleArg(args), e.code, e.message, e.code)
        }
        validationError?.let { message ->
            if (name == TOOL_START_SESSION) {
                runCatching { sessionStartRefusal?.invoke(args, message) }
            }
            return toolError(handleArg(args), "invalid_params", message, "invalid_params")
        }
        val handle = if (name == TOOL_START_SESSION) null else McpSessionArgs.runHandle(args)
        if (handle != null) {
            val snap = sessionResults?.snapshot(handle)
                ?: return toolError(handle, "unknown_handle", "unknown runHandle", "unknown_handle")
            if (snap.status.terminal && name != TOOL_FINISH_RUN) {
                return toolError(handle, "session_ended", "session already ${snap.status.wire}", "session_ended")
            }
        }
        return try {
            ToolOutcome(handle, "ok", toolOk(checkNotNull(sessionOps)(name, args)))
        } catch (e: McpTypedOpError) {
            toolError(handle, e.code, e.message, e.code)
        } catch (e: Exception) {
            toolError(handle, "operation_failed", "$name failed (${e.javaClass.simpleName})", "operation_failed")
        }
    }

    private fun runGoal(args: JsonObject): ToolOutcome {
        val goal = (args["goal"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (goal.isNullOrEmpty()) return toolError(null, "invalid_params", "goal must be a non-empty string")
        if (goal.length > MAX_GOAL_CHARS) {
            return toolError(null, "invalid_params", "goal is longer than $MAX_GOAL_CHARS characters")
        }
        return when (val r = backend.startRun(goal)) {
            is McpBackend.StartResult.Started ->
                ToolOutcome(r.runHandle, "started", toolOk(buildJsonObject { put("runHandle", r.runHandle) }))
            is McpBackend.StartResult.Refused -> toolError(null, "refused:${r.code}", r.message, r.code)
        }
    }

    private fun getRunResult(args: JsonObject): ToolOutcome {
        val handle = handleArg(args) ?: return toolError(null, "invalid_params", "runHandle must be a string")
        val waitEl = args["waitMs"]
        val waitMs = when {
            waitEl == null || waitEl is JsonNull -> 0L
            waitEl is JsonPrimitive && !waitEl.isString && waitEl.longOrNull != null -> waitEl.longOrNull!!
            waitEl is JsonPrimitive && !waitEl.isString && waitEl.content.toDoubleOrNull()?.isFinite() == true ->
                waitEl.content.toDouble().toLong()
            else -> return toolError(handle, "invalid_params", "waitMs must be a number")
        }.coerceIn(0L, McpRunRegistry.MAX_WAIT_MS)
        val snap = registry.await(handle, waitMs)
            ?: sessionResults?.await(handle, waitMs)
            ?: return toolError(handle, "unknown_handle", "unknown runHandle", "unknown_handle")
        return ToolOutcome(handle, snap.status.wire, toolOk(snapshotJson(snap)))
    }

    private fun stopRun(args: JsonObject): ToolOutcome {
        val handle = handleArg(args) ?: return toolError(null, "invalid_params", "runHandle must be a string")
        val before = registry.snapshot(handle) ?: sessionResults?.snapshot(handle)
            ?: return toolError(handle, "unknown_handle", "unknown runHandle", "unknown_handle")
        if (before.status.terminal) {
            return ToolOutcome(handle, "already_${before.status.wire}", toolOk(stopJson(false, before)))
        }
        val requested = backend.stopRun(handle)
        val after = registry.snapshot(handle) ?: sessionResults?.snapshot(handle) ?: before
        return ToolOutcome(
            handle,
            if (requested) "stop_requested" else "not_stoppable",
            toolOk(stopJson(requested, after)),
        )
    }

    private fun status(): ToolOutcome {
        val s = backend.status()
        return ToolOutcome(null, "ok", toolOk(buildJsonObject {
            put("version", s.version)
            put("serverOn", true)
            put("virtualDisplayReady", s.virtualDisplayReady)
            put("accessibilityReady", s.accessibilityReady)
            put("accessibilitySettingOn", s.accessibilitySettingOn)
            put("accessibilityBound", s.accessibilityReady)
            put("activeRuns", registry.activeCount() + (sessionResults?.activeCount() ?: 0))
            val adb = adbStatus()
            putJsonObject("adbChannel") {
                put("paired", adb.paired)
                put("connected", adb.connected)
                put("lastPort", adb.lastPort?.let { JsonPrimitive(it) } ?: JsonNull)
                put("lastSuccessfulPort", adb.lastPort?.let { JsonPrimitive(it) } ?: JsonNull)
                put("lastSuccessfulAt", adb.lastSuccessfulAt?.let { JsonPrimitive(it) } ?: JsonNull)
                put("discoveredPort", adb.discoveredPort?.let { JsonPrimitive(it) } ?: JsonNull)
                put("discoveredAt", adb.discoveredAt?.let { JsonPrimitive(it) } ?: JsonNull)
                put("discoverySource", adb.discoverySource?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            selfUpdate?.status()?.let {
                put("selfUpdateAdb", it.getValue("selfUpdateAdb"))
                put("selfUpdate", JsonObject(it - "selfUpdateAdb"))
            }
        }))
    }

    private fun selfUpdate(args: JsonObject): ToolOutcome {
        val update = checkNotNull(selfUpdate)
        val port = args["connectPort"]?.let {
            (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull
                ?.takeIf { value -> value in 1..65535 }
                ?: return toolError(null, "invalid_params", "connectPort must be an integer from 1 to 65535")
        }
        if (args.keys.any { it != "connectPort" }) {
            return toolError(null, "invalid_params", "Unexpected self_update argument")
        }
        val result = update.start(port)
        if (result["error"] == JsonPrimitive("adb_channel_unavailable")) {
            return toolError(null, "adb_channel_unavailable", "Built-in ADB channel is unavailable", "adb_channel_unavailable")
        }
        return ToolOutcome(null, "ok", toolOk(result))
    }

    private fun handleArg(args: JsonObject): String? = McpSessionArgs.runHandleOrNull(args)

    private fun snapshotJson(s: McpRunRegistry.Snapshot) = buildJsonObject {
        put("runHandle", s.runHandle)
        put("status", s.status.wire)
        put("summary", s.summary)
    }

    private fun stopJson(stopRequested: Boolean, s: McpRunRegistry.Snapshot) = buildJsonObject {
        put("runHandle", s.runHandle)
        put("stopRequested", stopRequested)
        put("status", s.status.wire)
    }

    // Tool result body, same shape as Shelly's toolResultBody: JSON text on success,
    // "Error: [code] message" with isError on failure.
    private fun toolOk(data: JsonObject) = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", data.toString())
            })
        }
        put("isError", false)
    }

    private fun toolError(handle: String?, outcome: String, message: String, code: String? = null): ToolOutcome {
        val prefix = code?.takeIf { CODE_RE.matches(it) }?.let { "[$it] " } ?: ""
        return ToolOutcome(handle, outcome, buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Error: $prefix$message")
                })
            }
            put("isError", true)
        })
    }

    private fun methodNotAllowed() =
        jsonResponse(405, buildJsonObject { put("error", "method not allowed") }, mapOf("Allow" to "POST"))

    private fun jsonResponse(status: Int, body: JsonElement, headers: Map<String, String> = emptyMap()) =
        McpHttpResponse(status, body.toString(), headers)

    private fun rpcResult(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun rpcError(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    /** Client-chosen names echoed into logs / errors: printable ASCII, capped. */
    private fun sanitize(value: String?): String =
        value?.filter { it in ' '..'~' }?.take(64) ?: "null"

    companion object {
        const val PATH = "/mcp"
        const val DEFAULT_PORT = 8768
        const val PROTOCOL_VERSION = "2025-06-18"
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26")
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val SERVER_NAME = "closepaw"
        const val MAX_SESSIONS = 64
        const val MAX_GOAL_CHARS = 4_000

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
        const val SESSION_ERROR = -32000

        const val TOOL_RUN_GOAL = "run_goal"
        const val TOOL_GET_RUN_RESULT = "get_run_result"
        const val TOOL_STOP_RUN = "stop_run"
        const val TOOL_STATUS = "closepaw_status"
        const val TOOL_SELF_UPDATE = "self_update"
        val TOOL_NAMES = setOf(TOOL_RUN_GOAL, TOOL_GET_RUN_RESULT, TOOL_STOP_RUN, TOOL_STATUS)

        const val TOOL_START_SESSION = "start_session"
        const val TOOL_OBSERVE = "observe"
        const val TOOL_TAP = "tap"
        const val TOOL_TYPE_TEXT = "type_text"
        const val TOOL_SYSTEM_BUTTON = "system_button"
        const val TOOL_OPEN_APP = "open_app"
        const val TOOL_WAIT = "wait"
        const val TOOL_FINISH_RUN = "finish_run"

        private val SESSION_OP_NAMES = setOf(
            TOOL_START_SESSION, TOOL_OBSERVE, TOOL_TAP, TOOL_TYPE_TEXT,
            TOOL_SYSTEM_BUTTON, TOOL_OPEN_APP, TOOL_WAIT, TOOL_FINISH_RUN,
        )

        private val CODE_RE = Regex("^[a-z_]{1,40}$|^[A-Z_]{1,40}$")
        private val JSON = Json { ignoreUnknownKeys = true }

        private const val INSTRUCTIONS =
            "ClosePaw operates apps on the user's Android phone. run_goal starts one task described " +
                "in natural language and returns a runHandle at once; poll get_run_result (waitMs up to " +
                "20000) until status is completed, failed or cancelled. stop_run stops it. Runs execute " +
                "on a background virtual display; one run at a time. " +
                "As an alternative to run_goal, start_session, observe, and the action tools let an external " +
                "driver operate a session step by step."

        private fun stringProp(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        val TOOLS: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("name", TOOL_RUN_GOAL)
                put(
                    "description",
                    "Starts one ClosePaw phone-operation run for a natural-language goal (e.g. 'open " +
                        "Settings and turn on Wi-Fi'). Returns {runHandle} at once; poll get_run_result.",
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") { put("goal", stringProp("What ClosePaw should do on the phone.")) }
                    putJsonArray("required") { add(JsonPrimitive("goal")) }
                    put("additionalProperties", false)
                }
            })
            add(buildJsonObject {
                put("name", TOOL_GET_RUN_RESULT)
                put(
                    "description",
                    "Returns {status, summary} for a runHandle. status: starting | running | completed | " +
                        "failed | cancelled. Waits up to waitMs (max 20000) for the run to finish.",
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("runHandle", stringProp("Handle returned by run_goal."))
                        putJsonObject("waitMs") {
                            put("type", "number")
                            put("description", "Long-poll up to this many ms (0-20000).")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("runHandle")) }
                    put("additionalProperties", false)
                }
            })
            add(buildJsonObject {
                put("name", TOOL_STOP_RUN)
                put("description", "Stops the run behind a runHandle.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") { put("runHandle", stringProp("Handle returned by run_goal.")) }
                    putJsonArray("required") { add(JsonPrimitive("runHandle")) }
                    put("additionalProperties", false)
                }
            })
            add(buildJsonObject {
                put("name", TOOL_STATUS)
                put(
                    "description",
                    "Returns service readiness, active runs, adbChannel, and self-update progress.",
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    put("additionalProperties", false)
                }
            })
        }
        private val TYPED_OP_NAMES = setOf("secure_setting", "read_logcat", "capture_screen", "get_ui_tree")

        private val TYPED_OP_TOOLS = buildJsonArray {
            add(buildJsonObject {
                put("name", "secure_setting")
                put("description", "Reads or writes an allowlisted Android setting.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("get"))
                                add(JsonPrimitive("put"))
                            }
                        }
                        putJsonObject("key") {
                            put("type", "string")
                            putJsonArray("enum") { secureSettingKeys.forEach { add(JsonPrimitive(it)) } }
                        }
                        put("value", stringProp("Value for put. accessibility_enabled accepts 0 or 1."))
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("action"))
                        add(JsonPrimitive("key"))
                    }
                    putJsonArray("anyOf") {
                        for (accessibility in listOf(false, true)) {
                            add(buildJsonObject {
                                putJsonObject("properties") {
                                    putJsonObject("action") {
                                        putJsonArray("enum") { add(JsonPrimitive("put")) }
                                    }
                                    putJsonObject("key") {
                                        putJsonArray("enum") {
                                            secureSettingKeys.filter {
                                                (it == "accessibility_enabled") == accessibility
                                            }.forEach { add(JsonPrimitive(it)) }
                                        }
                                    }
                                    if (accessibility) {
                                        putJsonObject("value") {
                                            putJsonArray("enum") {
                                                add(JsonPrimitive("0"))
                                                add(JsonPrimitive("1"))
                                            }
                                        }
                                    }
                                }
                                putJsonArray("required") {
                                    add(JsonPrimitive("action"))
                                    add(JsonPrimitive("key"))
                                    add(JsonPrimitive("value"))
                                }
                            })
                        }
                        add(buildJsonObject {
                            putJsonObject("properties") {
                                putJsonObject("action") {
                                    putJsonArray("enum") { add(JsonPrimitive("get")) }
                                }
                            }
                            putJsonArray("required") {
                                add(JsonPrimitive("action"))
                                add(JsonPrimitive("key"))
                            }
                            putJsonObject("not") {
                                putJsonArray("required") { add(JsonPrimitive("value")) }
                            }
                        })
                    }
                    put("additionalProperties", false)
                }
            })
            add(buildJsonObject {
                put("name", "read_logcat")
                put("description", "Reads a bounded snapshot of Android logcat.")
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("lines") {
                            put("type", "integer")
                            put("description", "Recent lines, must be between 1 and 1000. Defaults to 200.")
                            put("minimum", 1)
                            put("maximum", 1000)
                        }
                        putJsonObject("filter") {
                            put("type", "string")
                            put("description", "Optional logcat message regex (-e).")
                            put("maxLength", 512)
                            put("pattern", "^[^-\\u0000-\\u001F\\u007F-\\u009F][^\\u0000-\\u001F\\u007F-\\u009F]*$")
                        }
                        putJsonObject("packageName") {
                            put("type", "string")
                            put("description", "Optional package whose main process logs should be read.")
                            put("maxLength", 255)
                            put("pattern", "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$")
                        }
                    }
                    put("additionalProperties", false)
                }
            })
            for ((name, description) in listOf(
                "capture_screen" to "Captures the default display as a bounded base64 JPEG.",
                "get_ui_tree" to "Reads the bounded accessibility tree of the active window.",
            )) {
                add(buildJsonObject {
                    put("name", name)
                    put("description", description)
                    putJsonObject("inputSchema") {
                        put("type", "object")
                        putJsonObject("properties") {}
                        put("additionalProperties", false)
                    }
                })
            }
        }

        private val SESSION_OP_TOOLS = buildJsonArray {
            add(sessionTool(TOOL_START_SESSION, "Starts an externally driven session.", "goal") {
                put("goal", stringProp("What the session should accomplish."))
                put("reqId", stringProp("Optional request ID correlation label recorded with the session. " +
                    "No result callback is sent in this increment."))
                put("nonce", stringProp("Optional nonce correlation label recorded with the session. " +
                    "No result callback is sent in this increment."))
            })
            add(sessionTool(TOOL_OBSERVE, "Observes the current screen.") {
                putJsonObject("includeImage") {
                    put("type", "boolean")
                    put("description", "Also return a screenshot (default false; elements only)")
                }
            })
            add(sessionTool(TOOL_TAP, "Taps an observed element.", "elementIndex") {
                putJsonObject("elementIndex") {
                    put("type", "integer")
                    put("description", "Index of an element from the latest observe")
                }
            })
            add(sessionTool(TOOL_TYPE_TEXT, "Types text into the focused field.", "text") {
                put("text", stringProp("Text to type."))
                putJsonObject("clearFirst") { put("type", "boolean") }
            })
            add(sessionTool(TOOL_SYSTEM_BUTTON, "Presses the Back button.", "button") {
                putJsonObject("button") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("back")) }
                }
            })
            add(sessionTool(TOOL_OPEN_APP, "Opens an app.", "appName") {
                put("appName", stringProp("Name of the app to open."))
            })
            add(sessionTool(TOOL_WAIT, "Waits before the next session step.") {
                putJsonObject("durationMs") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("maximum", BrainSessionLimits.MAX_WAIT_MS)
                }
            })
            add(sessionTool(TOOL_FINISH_RUN, "Finishes the session.", "summary") {
                put("summary", stringProp("The brain's claimed outcome summary (unverified in this increment)"))
                putJsonObject("outcome") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("done"))
                        add(JsonPrimitive("failed"))
                    }
                    put("description", "Defaults to done. Use failed to mark the run failed with the summary as the reason.")
                }
            })
        }

        private fun sessionTool(
            name: String,
            description: String,
            vararg required: String,
            properties: JsonObjectBuilder.() -> Unit,
        ) = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    if (name != TOOL_START_SESSION) put("runHandle", stringProp("Handle returned by start_session."))
                    properties()
                }
                putJsonArray("required") {
                    if (name != TOOL_START_SESSION) add(JsonPrimitive("runHandle"))
                    required.forEach { add(JsonPrimitive(it)) }
                }
                put("additionalProperties", false)
            }
        }

        private val SELF_UPDATE_TOOL = buildJsonObject {
            put("name", TOOL_SELF_UPDATE)
            put("description", "Reinstall this ClosePaw build from its release APK. Poll closepaw_status for progress.")
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("connectPort") {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 65535)
                        put("description", "Wireless debugging connect port to try if automatic discovery fails.")
                    }
                }
                put("additionalProperties", false)
            }
        }
    }
}
