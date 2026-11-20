package ai.closepaw.mcp

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        val shizukuReady: Boolean,
        val accessibilityReady: Boolean,
    )

    /** Blocking; called on an HTTP worker thread. */
    fun startRun(goal: String): StartResult

    /** Asks the run behind [runHandle] to stop. False if it is not (or no longer) stoppable. */
    fun stopRun(runHandle: String): Boolean

    fun status(): Status
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
    /** SPIKE: null (default) = no spike tool and no `spike` status field. */
    private val spike: McpSpikeHook? = null,
) {
    private val tools: JsonArray = if (spike == null) TOOLS else JsonArray(TOOLS + SPIKE_TOOL + SPIKE_START_TOOL)
    private val toolNames: Set<String> =
        if (spike == null) TOOL_NAMES else TOOL_NAMES + TOOL_SPIKE_SELF_REINSTALL + TOOL_SPIKE_SHIZUKU_START

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
                val args = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
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
            TOOL_SPIKE_SELF_REINSTALL -> spikeSelfReinstall()
            TOOL_SPIKE_SHIZUKU_START -> spikeShizukuStart(args)
            else -> error("unreachable")
        }
        audit("mcp tool=$name handle=${out.handle ?: "-"} outcome=${out.outcome}")
        return out.result
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
            ?: return toolError(handle, "unknown_handle", "unknown runHandle", "unknown_handle")
        return ToolOutcome(handle, snap.status.wire, toolOk(snapshotJson(snap)))
    }

    private fun stopRun(args: JsonObject): ToolOutcome {
        val handle = handleArg(args) ?: return toolError(null, "invalid_params", "runHandle must be a string")
        val before = registry.snapshot(handle)
            ?: return toolError(handle, "unknown_handle", "unknown runHandle", "unknown_handle")
        if (before.status.terminal) {
            return ToolOutcome(handle, "already_${before.status.wire}", toolOk(stopJson(false, before)))
        }
        val requested = backend.stopRun(handle)
        val after = registry.snapshot(handle) ?: before
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
            put("shizukuReady", s.shizukuReady)
            put("accessibilityReady", s.accessibilityReady)
            put("activeRuns", registry.activeCount())
            spike?.let { hook ->
                put("spike", runCatching { hook.status() }.getOrElse { e ->
                    buildJsonObject { put("error", e.javaClass.simpleName) }
                })
                put("spikeStart", runCatching { hook.shizukuStartStatus() }.getOrElse { e ->
                    buildJsonObject { put("error", e.javaClass.simpleName) }
                })
            }
        }))
    }

    /** SPIKE: only reachable when [spike] is set (the tool is not listed otherwise). */
    private fun spikeSelfReinstall(): ToolOutcome {
        val hook = spike ?: return toolError(null, "unknown_tool", "spike tool not enabled")
        val current = hook.startSelfReinstall()
        val stage = ((current["current"] as? JsonObject)?.get("stage") as? JsonPrimitive)?.contentOrNull
        return ToolOutcome(null, "spike:${stage ?: "none"}", toolOk(current))
    }

    /** SPIKE: Shizuku-start probe. The audit line carries only the stage, never the arguments. */
    private fun spikeShizukuStart(args: JsonObject): ToolOutcome {
        val hook = spike ?: return toolError(null, "unknown_tool", "spike tool not enabled")
        val current = runCatching { hook.startShizukuStart(args) }.getOrElse { e ->
            return toolError(null, "spike_error", e.javaClass.simpleName)
        }
        (current["invalidArgs"] as? JsonPrimitive)?.contentOrNull?.let { msg ->
            return toolError(null, "invalid_params", msg)
        }
        val stage = ((current["current"] as? JsonObject)?.get("stage") as? JsonPrimitive)?.contentOrNull
        return ToolOutcome(null, "spike_start:${stage ?: "none"}", toolOk(current))
    }

    private fun handleArg(args: JsonObject): String? =
        (args["runHandle"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length in 1..128 }

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
        val TOOL_NAMES = setOf(TOOL_RUN_GOAL, TOOL_GET_RUN_RESULT, TOOL_STOP_RUN, TOOL_STATUS)
        const val TOOL_SPIKE_SELF_REINSTALL = "spike_self_reinstall"
        const val TOOL_SPIKE_SHIZUKU_START = "spike_shizuku_start"

        private val CODE_RE = Regex("^[a-z_]{1,40}$|^[A-Z_]{1,40}$")
        private val JSON = Json { ignoreUnknownKeys = true }

        private const val INSTRUCTIONS =
            "ClosePaw operates apps on the user's Android phone. run_goal starts one task described " +
                "in natural language and returns a runHandle at once; poll get_run_result (waitMs up to " +
                "20000) until status is completed, failed or cancelled. stop_run stops it. Runs execute " +
                "on a background virtual display; one run at a time."

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
                    "Returns {version, serverOn, shizukuReady, accessibilityReady, activeRuns}.",
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {}
                    put("additionalProperties", false)
                }
            })
        }

        /** SPIKE tool, listed only when the handler has a [McpSpikeHook]. */
        val SPIKE_TOOL: JsonObject = buildJsonObject {
            put("name", TOOL_SPIKE_SELF_REINSTALL)
            put(
                "description",
                "SPIKE: reinstalls this ClosePaw build from its own release APK via the Shizuku " +
                    "PackageInstaller session and returns the current stage at once. Progress (also " +
                    "across the restart) is in closepaw_status.spike.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {}
                put("additionalProperties", false)
            }
        }

        /** SPIKE tool, listed only when the handler has a [McpSpikeHook]. */
        val SPIKE_START_TOOL: JsonObject = buildJsonObject {
            put("name", TOOL_SPIKE_SHIZUKU_START)
            put(
                "description",
                "SPIKE (throwaway probe, not a product feature): pairs with / connects to this " +
                    "device's own wireless-debugging adbd on 127.0.0.1, runs Shizuku's official " +
                    "start.sh as the shell user and checks that the Shizuku binder answers. Returns " +
                    "the current stage at once; progress is in closepaw_status.spikeStart. pairPort + " +
                    "pairCode are only needed for the first pairing; pairCode is never recorded.",
            )
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("pairPort") {
                        put("type", "integer")
                        put("description", "Wireless debugging pairing port (with pairCode; first run only).")
                    }
                    putJsonObject("pairCode") {
                        put("type", "string")
                        put("description", "6-digit Wireless debugging pairing code (with pairPort).")
                    }
                    putJsonObject("connectPort") {
                        put("type", "integer")
                        put("description", "Wireless debugging connect port (IP address & Port screen).")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("connectPort")) }
                put("additionalProperties", false)
            }
        }
    }
}
