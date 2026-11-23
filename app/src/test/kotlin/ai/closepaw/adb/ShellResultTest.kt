package ai.closepaw.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ShellResultTest {
    @Test fun parsesOnlyTheFinalExitMarkerAndPreservesOutput() {
        assertEquals(
            ShellResult("literal __closepaw_rc=4\nlast line", 17),
            read("literal __closepaw_rc=4\nlast line\n__closepaw_rc=17\r\n"),
        )
        assertEquals(ShellResult("message\n__closepaw_rc=oops", null), read("message\n__closepaw_rc=oops"))
    }

    @Test fun keepsExitCodeBeyondTheOutputLimit() {
        val output = "x".repeat(70 * 1024)
        assertEquals(ShellResult(output.take(64 * 1024), 0, truncated = true), read("$output\n__closepaw_rc=0\n"))
    }

    @Test fun markerCanCrossTheOutputLimitWithoutLeakingIntoStdout() {
        val output = "x".repeat(64 * 1024 - 5)
        assertEquals(ShellResult(output, 3), read("$output\n__closepaw_rc=3\n"))
    }

    @Test fun waitsForTheCurrentCommandMarker() {
        val capture = ShellOutput(":current")
        val other = "stdout\n__closepaw_rc=0:other\n".toByteArray()
        capture.append(other, other.size)
        assertFalse(capture.hasExitMarker())
        val own = "\n__closepaw_rc=2:current\n".toByteArray()
        capture.append(own, own.size)
        assertTrue(capture.hasExitMarker())
        assertEquals(ShellResult(other.decodeToString(), 2), capture.result())
    }

    @Test fun shellIdentityRequiresTheMatchingNonceAndShellUid() {
        val output = "expected\r\n2000\r\n"
        assertTrue(isShellIdentityProbeValid(ShellResult(output, 0), "expected"))
        assertFalse(isShellIdentityProbeValid(ShellResult(output, 0), "different"))
        assertFalse(isShellIdentityProbeValid(ShellResult("expected\n0\n", 0), "expected"))
        assertFalse(isShellIdentityProbeValid(ShellResult(output, null), "expected"))
    }

    @Test fun acceptsAsciiShellCommand() {
        requireAsciiShellCommand("echo 'hello'\n# comment")
    }

    @Test fun acceptsEmptyShellCommand() {
        requireAsciiShellCommand("")
    }

    @Test fun rejectsEmDashInShellCommand() {
        val error = assertThrows(IOException::class.java) {
            requireAsciiShellCommand("echo '—日'")
        }
        assertEquals("Non-ASCII character in shell command (U+2014)", error.message)
    }

    @Test fun rejectsJapaneseCharacterInShellCommand() {
        val error = assertThrows(IOException::class.java) {
            requireAsciiShellCommand("echo '日'")
        }
        assertEquals("Non-ASCII character in shell command (U+65E5)", error.message)
    }

    private fun read(output: String): ShellResult {
        val capture = ShellOutput()
        output.toByteArray().asList().chunked(7).forEach {
            val chunk = it.toByteArray()
            capture.append(chunk, chunk.size)
        }
        return capture.result()
    }
}
