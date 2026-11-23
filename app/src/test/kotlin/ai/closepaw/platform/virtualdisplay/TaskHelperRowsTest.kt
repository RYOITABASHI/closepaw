package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperProtocol
import org.junit.Assert.*
import org.junit.Test

class TaskHelperRowsTest {
    @Test fun codecParity() {
        val encoded = TaskHelperProtocol.encodeRootTaskRows(listOf(
            TaskHelperProtocol.encodeRootTaskRow(9, null, null, emptyList(), null, false),
            TaskHelperProtocol.encodeRootTaskRow(2, "org.top", "org.base", listOf("org.top", "org.base"), true, true),
        ))
        val rows = listOf(
            TaskHelperRow(9, null, null, emptySet(), null, false),
            TaskHelperRow(2, "org.top", "org.base", setOf("org.top", "org.base"), true, true),
        )
        assertEquals(rows, TaskHelperRows.parse(encoded))
        assertEquals(rows.map { it.taskId }, TaskHelperProtocol.parseRootTaskRows(encoded).map { it[0].toInt() })
        assertNull(TaskHelperRows.parse(""))
        assertEquals("0\nEND", TaskHelperProtocol.encodeRootTaskRows(emptyList()))
        assertEquals(emptyList<TaskHelperRow>(), TaskHelperRows.parse("0\nEND"))
    }

    @Test fun rejectsMalformedSnapshots() {
        val row = "5\t-\t-\t\t-\tfalse"
        listOf(
            "garbage", row.dropLast(6), "$row\n", row.replaceFirst("5", "x"),
            row.replaceFirst("5", "2147483648"), row.replaceFirst("-", "bad-name"),
            row.replace("\t\t", "\torg.ok,\t"), row.replace("false", "FALSE"),
        ).forEach {
            assertNull(it, TaskHelperRows.parse(it))
            assertTrue(runCatching { TaskHelperProtocol.parseRootTaskRows(it) }.isFailure)
        }
        assertNull(TaskHelperRows.parse("$row\n$row"))

        val framed = TaskHelperProtocol.encodeRootTaskRows(listOf(
            TaskHelperProtocol.encodeRootTaskRow(5, null, null, emptyList(), null, false),
            TaskHelperProtocol.encodeRootTaskRow(7, "org.top", null, listOf("org.top"), true, false),
        ))
        val lines = framed.lines()
        listOf(
            listOf(lines[0], lines[1], lines.last()).joinToString("\n"),
            framed.removeSuffix("\nEND"),
            framed.replaceFirst("2\n", "3\n"),
            framed.replaceFirst("2\n", "1\n"),
            framed.dropLast(3),
            "$framed\n",
            "",
        ).forEach {
            assertNull(it, TaskHelperRows.parse(it))
            assertThrows(it, IllegalArgumentException::class.java) {
                TaskHelperProtocol.parseRootTaskRows(it)
            }
        }
    }

    @Test fun framedRowLevelRejections() {
        // Valid framing (count line + END terminator) around bad rows: rejection
        // must come from row decode / duplicate detection, not the framing checks.
        val duplicate = TaskHelperProtocol.encodeRootTaskRows(listOf(
            TaskHelperProtocol.encodeRootTaskRow(5, null, null, emptyList(), null, false),
            TaskHelperProtocol.encodeRootTaskRow(5, "org.top", null, listOf("org.top"), true, false),
        ))
        // Framing and per-row decode accept this payload (the wire codec has no
        // duplicate check); only TaskHelperRows' duplicate-taskId check rejects it.
        assertEquals(2, TaskHelperProtocol.parseRootTaskRows(duplicate).size)
        assertNull(TaskHelperRows.parse(duplicate))

        listOf(
            "1\n5\t-\t-\t\t-\tFALSE\nEND",          // pinned not a wire boolean
            "1\n5\tbad-name\t-\t\t-\tfalse\nEND",   // top package fails the pattern
            "1\n5\t-\t-\t\t-\nEND",                 // only five fields
        ).forEach {
            assertNull(it, TaskHelperRows.parse(it))
            assertThrows(it, IllegalArgumentException::class.java) {
                TaskHelperProtocol.parseRootTaskRows(it)
            }
        }
    }

    @Test fun replyInterpretation() {
        val row = TaskHelperRow(7, "org.top", null, setOf("org.top"), true, false)
        val payload = TaskHelperProtocol.encodeRootTaskRows(listOf(
            TaskHelperProtocol.encodeRootTaskRow(7, "org.top", null, listOf("org.top"), true, false),
        ))
        assertNull(TaskHelperRows.replyRows(queryNull = true, encodedRows = payload))
        assertNull(TaskHelperRows.replyRows(false, null))
        assertNull(TaskHelperRows.replyRows(true, null))
        assertEquals(listOf(row), TaskHelperRows.replyRows(false, payload))
        assertEquals(emptyList<TaskHelperRow>(), TaskHelperRows.replyRows(false, "0\nEND"))
    }
}
