package ai.closepaw.mcp

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class McpTypedOpPolicyTest {
    @Test
    fun secureSettingsMapToTheirAndroidNamespaces() {
        assertEquals(SecureSettingNamespace.SECURE, secureSettingNamespace("enabled_accessibility_services"))
        assertEquals(SecureSettingNamespace.SECURE, secureSettingNamespace("accessibility_enabled"))
        assertEquals(SecureSettingNamespace.GLOBAL, secureSettingNamespace("private_dns_mode"))
        assertEquals(SecureSettingNamespace.GLOBAL, secureSettingNamespace("private_dns_specifier"))
        assertNull(secureSettingNamespace("unknown"))
        assertNull(secureSettingNamespace(null))
    }

    @Test
    fun logLineCapKeepsNewestLinesOfMultilineOutput() {
        val bytes = "record header\nfirst continuation\nsecond continuation\nnewest\n".encodeToByteArray()
        val text = boundedLogText(bytes, limit = 40, maxLines = 2)
        assertEquals("second continuation\nnewest" + TRUNCATION_MARKER, text)
        assertTrue(text.encodeToByteArray().size <= 40)
    }

    @Test
    fun lineOnlyTruncationMarkerStaysOnTheFinalKeptLine() {
        val lineCapped = boundedLogText("a\nb\nc\n".encodeToByteArray(), maxLines = 2)
        assertEquals(listOf("b", "c [truncated]"), lineCapped.lines())
        assertEquals("", boundedLogText("a".encodeToByteArray(), maxLines = 0))
    }

    @Test
    fun byteCapKeepsNewestLogLines() {
        val bytes = (("a".repeat(900) + "\n").repeat(90) + "NEWEST\n").encodeToByteArray()
        val text = boundedLogText(bytes, maxLines = 1000)
        assertTrue(text.startsWith("[truncated] "))
        assertTrue(text.endsWith("NEWEST\n"))
        assertTrue(text.encodeToByteArray().size <= 64 * 1024)
        assertEquals(1, Regex("\\[truncated]").findAll(text).count())
    }

    @Test
    fun byteCapKeepsWholeMultibyteCharactersAndNewestLine() {
        for (character in listOf("あ", "😀")) {
            val bytes = ((character.repeat(30) + "\n").repeat(3) + "NEWEST").encodeToByteArray()
            val text = boundedLogText(bytes, limit = 32, maxLines = 1000)
            assertTrue(text.startsWith("[truncated] " + character))
            assertTrue(text.endsWith("\nNEWEST"))
            assertFalse(text.contains('\uFFFD'))
            assertTrue(text.encodeToByteArray().size <= 32)
            assertEquals(1, Regex("\\[truncated]").findAll(text).count())
        }
    }

    @Test
    fun byteCapSkipsDanglingCrLfLineFeed() {
        val bytes = ("a".repeat(30) + "\r\nxyz").encodeToByteArray()
        assertEquals("[truncated] xyz", boundedLogText(bytes, limit = 16, maxLines = 2))
    }

    @Test
    fun logLineCapPreservesExactCapIncludingTrailingNewline() {
        val text = "header\r\ncontinuation\r\n"
        assertEquals(text, boundedLogText(text.encodeToByteArray(), maxLines = 2))
    }

    @Test
    fun logLineAndByteCapsApplyTogether() {
        val text = boundedLogText("older\n${"あ".repeat(20)}\n".encodeToByteArray(), limit = 20, maxLines = 1)
        assertEquals("[truncated] ああ\n", text)
    }

    @Test
    fun missingReadLogsGrantReturnsSkipNote() {
        assertEquals("read_logs_not_granted: logcat not executed; READ_LOGS grant required",
            readLogsNotGrantedNote(false))
        assertNull(readLogsNotGrantedNote(true))
    }

    @Test
    fun processReturnsExitCodeAndStdout() {
        assumeTrue(File("/bin/sh").exists())
        val result = execProcess(arrayOf("/bin/sh", "-c", "printf 'a\\nb\\n'"),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(2), 64)
        assertEquals(0, result.exit)
        assertEquals("a\nb\n", result.text.decodeToString())
    }

    @Test
    fun processTimesOutPromptly() {
        assumeTrue(File("/bin/sh").exists())
        val start = System.nanoTime()
        assertThrows(TimeoutException::class.java) {
            execProcess(arrayOf("/bin/sh", "-c", "sleep 5"),
                start + TimeUnit.MILLISECONDS.toNanos(300), 64)
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
    }

    @Test
    fun processRetainsOnlyBoundedStdoutTail() {
        assumeTrue(File("/bin/sh").exists())
        val result = execProcess(arrayOf("/bin/sh", "-c", "printf '%020000dabcdefgh' 0; printf 'diagnostic' >&2"),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(5), 4)
        assertEquals(0, result.exit)
        assertEquals(4 + TAIL_SLACK, result.text.size)
        assertTrue(result.text.decodeToString().endsWith("abcdefgh"))
    }

    @Test
    fun processRetainsOnlyBoundedStdoutPrefix() {
        assumeTrue(File("/bin/sh").exists())
        val result = execProcess(arrayOf("/bin/sh", "-c", "printf '12345 %020000d' 0"),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(5), 8, CaptureMode.PREFIX)
        assertEquals(0, result.exit)
        assertEquals("12345 00", result.text.decodeToString())
    }

    @Test
    fun processLogFormattingKeepsNewestLinesBeyondTheCaptureWindow() {
        assumeTrue(File("/bin/sh").exists())
        val script = """
            i=1
            while [ "${'$'}i" -le 6000 ]; do
                printf 'log line %05d\n' "${'$'}i"
                i=${'$'}((i + 1))
            done
        """.trimIndent()
        val result = execProcess(arrayOf("/bin/sh", "-c", script),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(5), LOG_BYTE_LIMIT + 1)
        assertEquals(0, result.exit)
        assertEquals(listOf("log line 05999", "log line 06000 [truncated]"),
            boundedLogText(result.text, maxLines = 2).lines())
    }

    @Test(timeout = 3_000)
    fun processPropagatesLaunchFailure() {
        val failure = IOException("launch failed")
        val thrown = assertThrows(IOException::class.java) {
            execProcess(arrayOf("unused"), System.nanoTime() + TimeUnit.SECONDS.toNanos(2), 64) {
                throw failure
            }
        }
        assertEquals(failure, thrown)
    }

    @Test(timeout = 3_000)
    fun processPreservesStreamAccessFailureDuringCleanup() {
        val failure = IOException("stdout unavailable")
        val cleanupFailure = IOException("cleanup failed")
        var outputCloses = 0
        var destroyAttempts = 0
        var errorCloses = 0
        val child = object : Process() {
            private val stdin = object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun close() {
                    if (++outputCloses > 1) throw cleanupFailure
                }
            }
            private val stderr = object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() {
                    errorCloses++
                }
            }

            override fun getOutputStream(): OutputStream = stdin
            override fun getInputStream(): InputStream = throw failure
            override fun getErrorStream(): InputStream = stderr
            override fun waitFor(): Int = 0
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
            override fun exitValue(): Int = 0
            override fun isAlive(): Boolean = true
            override fun destroy() = error("Expected forced destruction")
            override fun destroyForcibly(): Process {
                destroyAttempts++
                throw cleanupFailure
            }
        }
        val thrown = assertThrows(IOException::class.java) {
            execProcess(arrayOf("unused"), System.nanoTime() + TimeUnit.SECONDS.toNanos(2), 64) { child }
        }
        assertEquals(failure, thrown)
        assertEquals(1, destroyAttempts)
        assertEquals(2, outputCloses)
        assertEquals(1, errorCloses)
    }

    @Test
    fun secureSettingsAllowOnlyExactKeysAndBooleanWrites() {
        for (key in listOf("enabled_accessibility_services", "accessibility_enabled", "private_dns_mode", "private_dns_specifier")) {
            assertNull(secureSettingError("get", key, null, false))
        }
        assertEquals("key_not_allowed", secureSettingError("get", "private_dns_mode ", null, false))
        assertEquals("permission_not_granted", secureSettingError("put", "private_dns_mode", "off", false))
        assertEquals("invalid_params", secureSettingError("put", "accessibility_enabled", "true", true))
        assertNull(secureSettingError("put", "accessibility_enabled", "1", true))
    }

    @Test
    fun logcatRejectsOutOfRangeLinesAndPassesRegexAsOneArgument() {
        for (lines in listOf(0, 2000)) {
            assertThrows(IllegalArgumentException::class.java) { logcatArgs(lines, null, null) }
        }
        assertEquals(listOf("logcat", "-d", "-t", "1000", "--pid=42", "-e", "a b; c"),
            logcatArgs(1000, "a b; c", 42).toList())
        assertEquals(listOf("logcat", "-d", "-t", "200"), logcatArgs(null, null, null).toList())
        assertThrows(IllegalArgumentException::class.java) { logcatArgs(10, "--pid=1", null) }
        assertThrows(IllegalArgumentException::class.java) { pidofArgs("-x") }
        assertEquals(listOf("pidof", "ai.closepaw"), pidofArgs("ai.closepaw").toList())
    }

    @Test
    fun logcatLinesAcceptsOnlyInRangeIntegralJsonNumbers() {
        for ((token, expected) in mapOf("1.0" to 1, "1000.0" to 1000, "1e2" to 100, "5" to 5)) {
            assertEquals(token, expected, parseLogLines(Json.parseToJsonElement(token)))
        }
        for (token in listOf("1.5", "0", "1001", "1001.0", "4294967297", "1e10000",
            "1.00000000000000001", "\"5\"", "true", "null", "{}")) {
            assertNull(token, parseLogLines(Json.parseToJsonElement(token)))
        }
    }

    @Test
    fun logcatFilterLengthCountsUnicodeCodePoints() {
        val character = "\uD83D\uDE00"
        for (count in listOf(300, 512)) {
            val filter = character.repeat(count)
            assertEquals(filter, logcatArgs(null, filter, null).last())
        }
        assertThrows(IllegalArgumentException::class.java) {
            logcatArgs(null, character.repeat(513), null)
        }
    }

    @Test
    fun truncationKeepsUtf8WholeAndIncludesMarkerInBudget() {
        val bytes = "あ".repeat(50).encodeToByteArray()
        val text = boundedLogText(bytes, 32)
        assertTrue(text.startsWith("[truncated] "))
        assertFalse(text.contains('\uFFFD'))
        assertTrue(text.encodeToByteArray().size <= 32)
        assertEquals("あ", boundedLogText("あ".encodeToByteArray(), 32))
    }

    @Test
    fun treeEnforcesDepthNodeAndEncodedByteLimits() {
        var reads = 0
        val depthLimited = boundedUiTree(0, { reads++; JsonObject(emptyMap()) }, { sequenceOf(it + 1) })
        assertEquals(31, reads)
        assertEquals("truncated", depthLimited["marker"]?.toString()?.trim('"'))

        reads = 0
        val nodeLimited = boundedUiTree(0, { reads++; JsonObject(emptyMap()) },
            { if (it == 0) (1..2000).asSequence() else emptySequence() })
        assertEquals(1500, reads)
        assertNotNull(nodeLimited["marker"])

        val byteLimited = boundedUiTree(0, { buildJsonObject { put("text", "\\あ".repeat(1000)) } },
            { if (it == 0) (1..2000).asSequence() else emptySequence() })
        assertNotNull(byteLimited["marker"])
        assertTrue(byteLimited.toString().encodeToByteArray().size <= 96 * 1024)
    }
}
