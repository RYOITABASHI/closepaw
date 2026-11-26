package ai.closepaw.mcp

import ai.closepaw.app.ShellyBackgroundRun
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Validates session tool arguments before dispatch. Accessors assume validation succeeded. */
object McpSessionArgs {
    internal enum class CorrelationPair { ABSENT, WELL_FORMED, MALFORMED }

    internal const val MIN_SWIPE_DURATION_MS = 50L
    internal const val MAX_SWIPE_DURATION_MS = 2_000L
    private const val MAX_CORRELATION_CHARS = 128
    private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    private val toolKeys = mapOf(
        McpHandler.TOOL_START_SESSION to setOf("goal", "reqId", "nonce"),
        McpHandler.TOOL_OBSERVE to setOf("runHandle", "includeImage"),
        McpHandler.TOOL_TAP to setOf("runHandle", "elementIndex"),
        McpHandler.TOOL_TAP_AT to setOf("runHandle", "x", "y"),
        McpHandler.TOOL_SWIPE to setOf("runHandle", "fromX", "fromY", "toX", "toY", "durationMs"),
        McpHandler.TOOL_TYPE_TEXT to setOf("runHandle", "text", "clearFirst"),
        McpHandler.TOOL_SYSTEM_BUTTON to setOf("runHandle", "button"),
        McpHandler.TOOL_OPEN_APP to setOf("runHandle", "appName"),
        McpHandler.TOOL_WAIT to setOf("runHandle", "durationMs"),
        McpHandler.TOOL_FINISH_RUN to setOf("runHandle", "summary", "outcome"),
    )

    fun validate(name: String, args: JsonObject): String? {
        val allowed = toolKeys[name] ?: return "unknown session tool"
        if (!allowed.containsAll(args.keys)) return "unexpected argument"
        if (name != McpHandler.TOOL_START_SESSION && runHandleOrNull(args) == null) {
            return "runHandle must be a string of 1 to 128 characters"
        }
        return when (name) {
            McpHandler.TOOL_START_SESSION ->
                requiredNonBlank(args, "goal", McpHandler.MAX_GOAL_CHARS)
                    ?: optionalString(args, "reqId", MAX_CORRELATION_CHARS)
                    ?: optionalString(args, "nonce", MAX_CORRELATION_CHARS)
                    ?: if (classifyCorrelationPair(args) == CorrelationPair.MALFORMED) {
                        "reqId and nonce must be supplied together in the Shelly correlation format"
                    } else null
            McpHandler.TOOL_OBSERVE -> optionalBoolean(args, "includeImage")
            McpHandler.TOOL_TAP ->
                if (integer(args, "elementIndex", Int.MAX_VALUE.toLong()) == null) {
                    "elementIndex must be an integer between 0 and ${Int.MAX_VALUE}"
                } else null
            McpHandler.TOOL_TAP_AT ->
                coordinateError(args, "x") ?: coordinateError(args, "y")
            McpHandler.TOOL_SWIPE ->
                coordinateError(args, "fromX") ?: coordinateError(args, "fromY")
                    ?: coordinateError(args, "toX") ?: coordinateError(args, "toY")
                    ?: if (swipeDurationOrNull(args) == null) {
                        "durationMs must be an integer between $MIN_SWIPE_DURATION_MS and $MAX_SWIPE_DURATION_MS"
                    } else null
            McpHandler.TOOL_TYPE_TEXT ->
                stringLimitError("text", string(args, "text"), BrainSessionLimits.MAX_TYPE_TEXT_CHARS)
                    ?: optionalBoolean(args, "clearFirst")
            McpHandler.TOOL_SYSTEM_BUTTON ->
                if (normalizedButton(args) != "back") "button must be back" else null
            McpHandler.TOOL_OPEN_APP -> requiredNonBlank(args, "appName", BrainSessionLimits.MAX_APP_NAME_CHARS)
            McpHandler.TOOL_WAIT ->
                if ("durationMs" in args && integer(args, "durationMs", BrainSessionLimits.MAX_WAIT_MS) == null) {
                    "durationMs must be an integer between 0 and ${BrainSessionLimits.MAX_WAIT_MS}"
                } else null
            McpHandler.TOOL_FINISH_RUN -> {
                outcome(args)
                requiredString(args, "summary", BrainSessionLimits.MAX_SUMMARY_CHARS)
            }
            else -> error("unreachable")
        }
    }

    fun goal(args: JsonObject): String = checkNotNull(string(args, "goal"))
    fun reqId(args: JsonObject): String? = string(args, "reqId")
    fun nonce(args: JsonObject): String? = string(args, "nonce")

    internal fun classifyCorrelationPair(args: JsonObject): CorrelationPair = when {
        "reqId" !in args && "nonce" !in args -> CorrelationPair.ABSENT
        ShellyBackgroundRun.parseResultCorrelation(reqId(args), nonce(args)) != null -> CorrelationPair.WELL_FORMED
        else -> CorrelationPair.MALFORMED
    }

    fun runHandle(args: JsonObject): String = checkNotNull(runHandleOrNull(args))
    fun elementIndex(args: JsonObject): Int = checkNotNull(integer(args, "elementIndex", Int.MAX_VALUE.toLong())).toInt()
    internal fun coordinate(args: JsonObject, key: String): Int =
        checkNotNull(integer(args, key, Int.MAX_VALUE.toLong())).toInt()
    internal fun swipeDurationMs(args: JsonObject): Long = checkNotNull(swipeDurationOrNull(args))
    fun text(args: JsonObject): String = checkNotNull(string(args, "text"))
    fun clearFirst(args: JsonObject): Boolean = args["clearFirst"] == JsonPrimitive(true)
    fun button(args: JsonObject): String = checkNotNull(normalizedButton(args))
    fun appName(args: JsonObject): String = checkNotNull(string(args, "appName"))
    fun durationMs(args: JsonObject): Long = integer(args, "durationMs", BrainSessionLimits.MAX_WAIT_MS) ?: 1_000L
    fun includeImage(args: JsonObject): Boolean = args["includeImage"] == JsonPrimitive(true)
    fun summary(args: JsonObject): String = checkNotNull(string(args, "summary"))

    fun outcome(args: JsonObject): String {
        if ("outcome" !in args) return "done"
        return when (val value = string(args, "outcome")) {
            "done", "failed" -> value
            else -> throw McpTypedOpError("invalid_params", "outcome must be done or failed")
        }
    }

    internal fun runHandleOrNull(args: JsonObject): String? =
        string(args, "runHandle")?.takeIf { it.length in 1..128 }

    private fun string(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun normalizedButton(args: JsonObject): String? = string(args, "button")?.trim()?.lowercase()

    private fun stringLimitError(key: String, value: String?, max: Int): String? = when {
        value == null -> "$key must be a string"
        value.length > max -> "$key is longer than $max characters"
        else -> null
    }

    private fun requiredNonBlank(args: JsonObject, key: String, max: Int): String? {
        val value = string(args, key)
        if (value.isNullOrBlank()) return "$key must be a non-blank string"
        return stringLimitError(key, value, max)
    }

    private fun requiredString(args: JsonObject, key: String, max: Int): String? {
        val value = string(args, key)
        return if (value == null || value.length > max) "$key must be a string of at most $max characters" else null
    }

    private fun optionalString(args: JsonObject, key: String, max: Int): String? =
        if (key in args) stringLimitError(key, string(args, key), max) else null

    private fun optionalBoolean(args: JsonObject, key: String): String? {
        val value = args[key]
        return if (value == null || value == JsonPrimitive(true) || value == JsonPrimitive(false)) null
        else "$key must be a boolean"
    }

    private fun coordinateError(args: JsonObject, key: String): String? =
        if (integer(args, key, Int.MAX_VALUE.toLong()) == null) {
            "$key must be an integer between 0 and ${Int.MAX_VALUE}"
        } else null

    private fun swipeDurationOrNull(args: JsonObject): Long? =
        integer(args, "durationMs", MAX_SWIPE_DURATION_MS)?.takeIf { it >= MIN_SWIPE_DURATION_MS }

    private fun integer(args: JsonObject, key: String, max: Long): Long? {
        val token = (args[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content ?: return null
        if (!jsonNumber.matches(token)) return null
        val number = token.toBigDecimalOrNull() ?: return null
        // Exact conversion handles decimal and exponent notation without rounding fractions.
        val value = try {
            number.longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        return value.takeIf { it in 0..max }
    }
}
