package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperProtocol

internal data class TaskHelperRow(
        val taskId: Int,
        val topPackage: String?,
        val basePackage: String?,
        val packages: Set<String>,
        val visible: Boolean?,
        val pinned: Boolean,
) {
    val packageName: String? get() = topPackage ?: basePackage
}

internal object TaskHelperRows {
    fun parse(encoded: String): List<TaskHelperRow>? {
        val fields = try {
            TaskHelperProtocol.parseRootTaskRows(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val taskIds = HashSet<Int>()
        return fields.map { row ->
            val taskId = Integer.parseInt(row[0])
            if (!taskIds.add(taskId)) return null
            TaskHelperRow(
                    taskId,
                    row[1].takeUnless { it == "-" },
                    row[2].takeUnless { it == "-" },
                    if (row[3].isEmpty()) emptySet() else row[3].split(',').toSet(),
                    row[4].takeUnless { it == "-" }?.toBooleanStrict(),
                    row[5].toBooleanStrict(),
            )
        }
    }

    /** Interprets a ROOT_TASKS reply: an explicit QUERY_NULL signal or a missing rows payload is a failed query (null); otherwise the framed rows payload is parsed strictly. */
    fun replyRows(queryNull: Boolean, encodedRows: String?): List<TaskHelperRow>? =
            if (queryNull || encodedRows == null) null else parse(encodedRows)
}
