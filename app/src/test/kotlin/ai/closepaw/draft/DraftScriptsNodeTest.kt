package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs `src/test/js/draft-scripts.test.js` when Node is installed. The JVM has no JavaScript
 * engine, and the spoofing cases for the save check live in that file. CI without Node skips this
 * rather than fails; the pinned script text still guards any change.
 */
class DraftScriptsNodeTest {

    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().let { process ->
            process.inputStream.readBytes()
            process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0
        }
    } catch (e: java.io.IOException) {
        false
    }

    @Test
    fun `the scripts pass their fake DOM cases`() {
        assumeTrue("node is not installed", nodeAvailable())
        val process = ProcessBuilder("node", "src/test/js/draft-scripts.test.js", "src/test/resources/draft-scripts")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue()
        assertThat(output).contains("script tests passed")
        assertThat(process.exitValue()).isEqualTo(0)
    }
}
