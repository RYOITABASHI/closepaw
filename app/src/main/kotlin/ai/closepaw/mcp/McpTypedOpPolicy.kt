package ai.closepaw.mcp

import java.io.InputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class McpTypedOpError(val code: String, override val message: String) : Exception(message)

internal enum class SecureSettingNamespace { SECURE, GLOBAL }

private val SECURE_SETTINGS = mapOf(
    "enabled_accessibility_services" to SecureSettingNamespace.SECURE,
    "accessibility_enabled" to SecureSettingNamespace.SECURE,
    "private_dns_mode" to SecureSettingNamespace.GLOBAL,
    "private_dns_specifier" to SecureSettingNamespace.GLOBAL,
)

internal val secureSettingKeys: List<String> = SECURE_SETTINGS.keys.toList()

internal fun secureSettingNamespace(key: String?): SecureSettingNamespace? = SECURE_SETTINGS[key]

internal fun secureSettingError(action: String?, key: String?, value: String?, canWrite: Boolean): String? =
    when {
        key == null -> "invalid_params"
        secureSettingNamespace(key) == null -> "key_not_allowed"
        action == "get" && value == null -> null
        action != "put" -> "invalid_params"
        !canWrite -> "permission_not_granted"
        value == null || (key == "accessibility_enabled" && value !in setOf("0", "1")) -> "invalid_params"
        else -> null
    }

private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

internal fun validateLogcatPackage(packageName: String) {
    require(packageName.length <= 255 && PACKAGE_NAME.matches(packageName))
}

internal fun pidofArgs(packageName: String): Array<String> {
    validateLogcatPackage(packageName)
    return arrayOf("pidof", packageName)
}

internal fun effectiveLogLines(lines: Int?): Int = lines ?: 200

private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

internal fun parseLogLines(value: JsonElement): Int? {
    val token = (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content ?: return null
    if (!JSON_NUMBER.matches(token)) return null
    val number = token.toBigDecimalOrNull() ?: return null
    // Exact conversion rejects fractional values and overflow without rounding.
    val lines = try {
        number.intValueExact()
    } catch (_: ArithmeticException) {
        return null
    }
    return lines.takeIf { it in 1..1000 }
}

internal fun validateLogcatOptions(lines: Int?, filter: String?) {
    require(lines == null || lines in 1..1000)
    require(filter == null || (filter.codePointCount(0, filter.length) in 1..512 && !filter.startsWith('-') &&
        filter.none(Char::isISOControl)))
}

internal fun readLogsNotGrantedNote(hasReadLogs: Boolean): String? =
    if (hasReadLogs) null else "read_logs_not_granted: logcat not executed; READ_LOGS grant required"

internal fun logcatArgs(lines: Int?, filter: String?, pid: Int?): Array<String> {
    validateLogcatOptions(lines, filter)
    require(pid == null || pid > 0)
    return buildList {
        addAll(listOf("logcat", "-d", "-t", effectiveLogLines(lines).toString()))
        if (pid != null) add("--pid=$pid")
        if (filter != null) {
            add("-e")
            add(filter)
        }
    }.toTypedArray()
}

internal const val LOG_BYTE_LIMIT = 64 * 1024
internal const val TRUNCATION_MARKER = " [truncated]"

// A byte cut keeps the tail and prefixes its first line with "[truncated] ".
// Otherwise, truncation appends " [truncated]" to the final kept line.
internal fun boundedLogText(
    bytes: ByteArray,
    limit: Int = LOG_BYTE_LIMIT,
    maxLines: Int = Int.MAX_VALUE,
): String {
    require(maxLines >= 0)
    if (maxLines == 0) return ""
    val decoded = bytes.decodeToString()
    // A final line terminator ends the last line without starting another.
    var start = 0
    var remaining = maxLines
    for (index in decoded.withoutFinalLineTerminator().length - 1 downTo 0) {
        val char = decoded[index]
        if (char == '\n' || (char == '\r' && decoded.getOrNull(index + 1) != '\n')) {
            remaining--
            if (remaining == 0) {
                start = index + 1
                break
            }
        }
    }
    val linesDropped = start > 0
    val text = decoded.substring(start)
    // Decode malformed bytes with replacement, then cap the resulting UTF-8 bytes.
    val encoded = text.encodeToByteArray()
    if (!linesDropped && bytes.size <= limit && encoded.size <= limit) return text
    require(limit >= TRUNCATION_MARKER.length)
    val finalLineText = text.withoutFinalLineTerminator()
    val space = limit - TRUNCATION_MARKER.length
    if (encoded.size <= space + (text.length - finalLineText.length)) {
        return finalLineText + TRUNCATION_MARKER
    }

    var tailStart = encoded.size - space
    while (tailStart < encoded.size && encoded[tailStart].toInt() and 0xc0 == 0x80) tailStart++
    if (tailStart < encoded.size && encoded[tailStart] == '\n'.code.toByte() &&
        encoded[tailStart - 1] == '\r'.code.toByte()
    ) {
        tailStart++
    }
    return "[truncated] " + encoded.decodeToString(startIndex = tailStart)
}

private fun String.withoutFinalLineTerminator(): String = when {
    endsWith("\r\n") -> dropLast(2)
    endsWith('\n') || endsWith('\r') -> dropLast(1)
    else -> this
}

// Give boundedLogText the true end of stdout plus room to find line boundaries.
internal const val TAIL_SLACK = 8 * 1024

internal data class ProcessResult(val exit: Int, val text: ByteArray)

internal enum class CaptureMode { PREFIX, TAIL }

/** Captures the first limit or last limit + TAIL_SLACK bytes. Nonpositive limits only drain. */
internal fun execProcess(
    argv: Array<String>,
    deadline: Long,
    limit: Int,
    captureMode: CaptureMode = CaptureMode.TAIL,
    launch: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) },
): ProcessResult {
    var process: Process? = null
    var stdout: FutureTask<ByteArray>? = null
    var stderr: FutureTask<ByteArray>? = null
    var stdoutThread: Thread? = null
    var stderrThread: Thread? = null
    try {
        val child = launch(argv)
        process = child
        child.outputStream.close()
        val output = child.inputStream
        stdout = FutureTask { readOutput(output, child, limit, captureMode) }
        stderr = FutureTask { readOutput(child.errorStream, child, 0, CaptureMode.TAIL) }
        stdoutThread = Thread(stdout, "closepaw-mcp-process-stdout").apply { isDaemon = true }
        stderrThread = Thread(stderr, "closepaw-mcp-process-stderr").apply { isDaemon = true }
        stdoutThread.start()
        stderrThread.start()
        if (!child.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) throw TimeoutException()
        val text = stdout.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
        stderr.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
        return ProcessResult(child.exitValue(), text)
    } finally {
        process?.let { child ->
            try {
                if (child.isAlive) child.destroyForcibly()
            } catch (ignored: Throwable) {
            }
            try {
                child.outputStream.close()
            } catch (ignored: Throwable) {
            }
            try {
                child.inputStream.close()
            } catch (ignored: Throwable) {
            }
            try {
                child.errorStream.close()
            } catch (ignored: Throwable) {
            }
        }
        try {
            stdout?.cancel(true)
        } catch (ignored: Throwable) {
        }
        try {
            stderr?.cancel(true)
        } catch (ignored: Throwable) {
        }
        var interrupted = false
        try {
            stdoutThread?.let { if (it.state != Thread.State.NEW) it.join(1_000) }
        } catch (ignored: Throwable) {
            if (ignored is InterruptedException) interrupted = true
        }
        try {
            stderrThread?.let { if (it.state != Thread.State.NEW) it.join(1_000) }
        } catch (ignored: Throwable) {
            if (ignored is InterruptedException) interrupted = true
        }
        if (interrupted) {
            try {
                Thread.currentThread().interrupt()
            } catch (ignored: Throwable) {
            }
        }
    }
}

/** Polls available bytes while retaining the requested portion of the stream. */
private fun readOutput(input: InputStream, process: Process, limit: Int, mode: CaptureMode): ByteArray {
    val retain = limit > 0
    val prefix = retain && mode == CaptureMode.PREFIX
    val buffer = ByteArray(when {
        prefix -> limit
        retain -> limit + TAIL_SLACK
        else -> 4096
    })
    val discard = if (prefix) ByteArray(4096) else buffer
    var next = 0
    var wrapped = false
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        // A descendant can keep a pipe open after the direct child exits. Read only
        // available bytes so cleanup never has to wait for that descendant to close it.
        val running = process.isAlive
        val available = input.available()
        if (available == 0) {
            if (!running) break
            Thread.sleep(10)
            continue
        }
        if (prefix && next == buffer.size) {
            if (input.read(discard, 0, minOf(available, discard.size)) == -1) break
            continue
        }
        val count = input.read(buffer, next, minOf(available, buffer.size - next))
        if (count == -1) break
        if (retain) {
            next += count
            if (!prefix && next == buffer.size) {
                next = 0
                wrapped = true
            }
        }
    }
    if (!wrapped) return buffer.copyOf(next)
    return ByteArray(buffer.size).also { tail ->
        buffer.copyInto(tail, startIndex = next)
        buffer.copyInto(tail, destinationOffset = buffer.size - next, endIndex = next)
    }
}

internal fun <T> boundedUiTree(
    root: T,
    fields: (T) -> JsonObject,
    children: (T) -> Sequence<T>,
): JsonObject {
    val prefix = """{"root":"""
    val truncatedSuffix = ""","truncated":true,"marker":"truncated"}"""
    val completeSuffix = ""","truncated":false}"""
    val output = StringBuilder(prefix)
    var available = 96 * 1024 - prefix.length - truncatedSuffix.length
    var count = 0
    var truncated = false
    var outOfSpace = false

    fun appendNode(node: T, depth: Int, comma: Boolean): Boolean {
        if (depth > 30 || count >= 1500) {
            truncated = true
            return false
        }
        val data = fields(node)
        val head = data.toString().dropLast(1) +
            (if (data.isEmpty()) "" else ",") + "\"children\":["
        val cost = head.encodeToByteArray().size + 2 + if (comma) 1 else 0
        if (cost > available) {
            truncated = true
            outOfSpace = true
            return false
        }
        available -= cost
        count++
        if (comma) output.append(',')
        output.append(head)
        var hasChild = false
        for (child in children(node)) {
            if (!appendNode(child, depth + 1, hasChild)) break
            hasChild = true
            if (outOfSpace) break
        }
        output.append("]}")
        return true
    }

    if (!appendNode(root, 0, false)) output.append("null")
    output.append(if (truncated) truncatedSuffix else completeSuffix)
    return Json.parseToJsonElement(output.toString()).jsonObject
}
