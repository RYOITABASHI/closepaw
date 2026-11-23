package ai.closepaw.platform.virtualdisplay

internal object TaskRowMapping {
    fun toDisplayTasks(rows: List<TaskHelperRow>): List<DisplayTask> =
            rows.map { DisplayTask(it.taskId, it.packageName, it.pinned) }

    fun toRootTasks(rows: List<TaskHelperRow>): List<ShizukuActivityTaskTransport.RootTask> =
            rows.map { ShizukuActivityTaskTransport.RootTask(it.taskId, it.packages, it.visible) }

    fun toTaskIds(rows: List<TaskHelperRow>, packageName: String): Set<Int> =
            rows.filter { packageName in it.packages }.mapTo(linkedSetOf()) { it.taskId }
}
