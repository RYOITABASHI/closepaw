package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TaskHelperPayloadHygieneTest {
    @Test fun taskHelperProcessSourceIsAscii() {
        val relativePath = "src/main/kotlin/ai/closepaw/platform/virtualdisplay/TaskHelperProcess.kt"
        val candidates = listOf(File(relativePath), File("app", relativePath))
        val source = candidates.firstOrNull { it.isFile }
            ?: error("TaskHelperProcess.kt not found at: ${candidates.joinToString { it.absolutePath }}")
        val firstNonAsciiByte = source.readBytes().indexOfFirst { it < 0 }
        assertEquals("First non-ASCII byte offset in ${source.path}", -1, firstNonAsciiByte)
    }
}
