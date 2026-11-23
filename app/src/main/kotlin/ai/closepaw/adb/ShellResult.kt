package ai.closepaw.adb

import java.io.IOException

internal data class ShellResult(val stdout: String, val exitCode: Int?, val truncated: Boolean = false)

internal fun isShellIdentityProbeValid(result: ShellResult, nonce: String): Boolean =
    result.exitCode == 0 && !result.truncated && result.stdout.lineSequence().toList() ==
        listOf(nonce, "2000", "")

internal fun requireAsciiShellCommand(command: String) {
    val index = command.indexOfFirst { it.code > 0x7F }
    if (index >= 0) {
        val codePoint = Character.codePointAt(command, index).toString(16).uppercase().padStart(4, '0')
        throw IOException("Non-ASCII character in shell command (U+$codePoint)")
    }
}

private const val RC_MARKER = "__closepaw_rc"

internal fun withExitCode(command: String, markerSuffix: String): String =
    "(\n$command\n) 2>&1; printf '\\n${RC_MARKER}=%s$markerSuffix\\n' \"\$?\""

/** Keeps the stdout prefix and enough of the tail for the exit marker, even after truncation. */
internal class ShellOutput(markerSuffix: String = "") {
    private val prefix = ByteArray(64 * 1024)
    private val tail = ByteArray(64)
    private var total = 0L
    private var tailSize = 0
    private val marker = Regex("""\r?\n$RC_MARKER=(\d{1,3})${Regex.escape(markerSuffix)}\r?\n\z""")

    fun append(bytes: ByteArray, count: Int) {
        if (total < prefix.size) {
            val offset = total.toInt()
            bytes.copyInto(prefix, offset, 0, minOf(count, prefix.size - offset))
        }
        val incoming = minOf(count, tail.size)
        val retained = minOf(tailSize, tail.size - incoming)
        tail.copyInto(tail, 0, tailSize - retained, tailSize)
        bytes.copyInto(tail, retained, count - incoming, count)
        tailSize = retained + incoming
        total += count
    }

    fun hasExitMarker(): Boolean = exitMarker() != null

    // ISO-8859-1 keeps marker offsets in bytes even when stdout contains multibyte UTF-8.
    private fun exitMarker() = marker.find(String(tail, 0, tailSize, Charsets.ISO_8859_1))

    fun result(): ShellResult {
        val match = exitMarker()
        val end = match?.let { total - tailSize + it.range.first } ?: total
        val length = minOf(end, prefix.size.toLong()).toInt()
        val stdout = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.IGNORE)
            .decode(java.nio.ByteBuffer.wrap(prefix, 0, length)).toString()
        return ShellResult(stdout, match?.groupValues?.get(1)?.toInt(), truncated = end > prefix.size)
    }
}
