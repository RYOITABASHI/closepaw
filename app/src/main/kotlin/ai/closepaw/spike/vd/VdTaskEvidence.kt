package ai.closepaw.spike.vd

import ai.closepaw.adb.ShellResult

private val component = Regex("""([\w.]+)/[\w.\$]+""")
private val rootLine = Regex("""RootTask id=(\d+) bounds=.* displayId=(\d+) userId=\d+""")
private val childLine = Regex("""taskId=(\d+): (.*?) userId=\d+ visible=(true|false)(?: topActivity=ComponentInfo\{([^}]+)\})?""")
private val mode = Regex("""mWindowingMode=(\w+)""")
private val taskVisibility = Regex("""(?:^| )visible=(true|false)(?: |\})""")
private val intentComponent = Regex("""(?:^| )I=([\w.]+)/[\w.\$]+(?: |\})""")
private val diagnostics = Regex(
    """(?im)^\s*(error(?:\b|:)|exception\b|security exception|permission denial|can't find service|unknown command|dumpsys:|failure\b|\*+.*dump timeout)"""
)

internal fun ShellResult.completeOutput(): String? =
    stdout.takeIf { exitCode == 0 && !truncated && !diagnostics.containsMatchIn(it) }

internal data class StackRoot(
    val id: Int,
    val display: Int,
    val childIds: Set<Int> = emptySet(),
    val childPackages: Set<String> = emptySet(),
    val pinned: Boolean? = null,
    val visible: Boolean? = null,
    val topPackage: String? = null,
)

internal fun parseStack(result: ShellResult): List<StackRoot>? {
    val output = result.completeOutput() ?: return null
    val roots = mutableListOf<StackRoot>()
    var current: StackRoot? = null
    for (raw in output.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val header = rootLine.matchEntire(line)
        if (header != null) {
            current?.let(roots::add)
            val id = header.groupValues[1].toIntOrNull() ?: return null
            val display = header.groupValues[2].toIntOrNull() ?: return null
            current = StackRoot(id, display)
            continue
        }
        val root = current ?: return null
        if (line.startsWith("configuration=")) {
            current = root.copy(pinned = mode.find(line)?.groupValues?.get(1)?.let { it == "pinned" })
            continue
        }
        val child = childLine.matchEntire(line) ?: return null
        val id = child.groupValues[1].toIntOrNull() ?: return null
        val visible = child.groupValues[3].toBooleanStrict()
        val top = child.groupValues[4].takeIf(String::isNotEmpty)?.let {
            component.matchEntire(it)?.groupValues?.get(1) ?: return null
        }
        if (id in root.childIds || (root.childIds.isNotEmpty() && (root.visible != visible || root.topPackage != top))) {
            return null
        }
        current = root.copy(
            childIds = root.childIds + id,
            childPackages = root.childPackages + component.findAll(child.groupValues[2]).map { it.groupValues[1] },
            visible = visible,
            topPackage = top,
        )
    }
    current?.let(roots::add)
    return roots.takeIf { it.map(StackRoot::id).distinct().size == it.size }
}

private val displayLine = Regex("""Display #(\d+) \(activities from top to bottom\):""")
private val taskLine = Regex("""\* Task\{\S+ #(\d+)(?:\s|\}).*""")
private val historyLine = Regex("""\* Hist #\d+: ActivityRecord\{\S+ u\d+ (\S+) t(\d+)(?:\s|\}).*""")

internal data class DumpTask(
    val id: Int,
    val display: Int,
    val parentId: Int?,
    val activities: MutableList<String> = mutableListOf(),
    val intentPackage: String? = null,
    val visible: Boolean? = null,
)

internal data class ActivityDump(val displays: Set<Int>, val tasks: List<DumpTask>)

internal fun parseActivities(result: ShellResult): ActivityDump? {
    val output = result.completeOutput() ?: return null
    val displays = linkedSetOf<Int>()
    val tasks = mutableListOf<DumpTask>()
    val parents = mutableListOf<Pair<Int, DumpTask>>()
    var display: Int? = null
    for (raw in output.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("Display #")) {
            display = displayLine.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (!displays.add(display)) return null
            parents.clear()
            continue
        }
        if (display == null || line.isEmpty()) continue
        val indent = raw.indexOfFirst { !it.isWhitespace() }
        while (parents.lastOrNull()?.first?.let { it >= indent } == true) parents.removeAt(parents.lastIndex)
        if (line.startsWith("* Task{")) {
            val id = taskLine.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val task = DumpTask(
                id, display, parents.lastOrNull()?.second?.id,
                intentPackage = intentComponent.find(line)?.groupValues?.get(1),
                visible = taskVisibility.find(line)?.groupValues?.get(1)?.toBooleanStrict(),
            )
            tasks.add(task)
            parents.add(indent to task)
            continue
        }
        val task = parents.lastOrNull()?.second ?: continue
        if (line.startsWith("* Hist #")) {
            val history = historyLine.matchEntire(line) ?: return null
            if (history.groupValues[2].toIntOrNull() != task.id) return null
            task.activities.add(history.groupValues[1])
            continue
        }
    }
    if (displays.isEmpty() || tasks.map(DumpTask::id).distinct().size != tasks.size) return null
    return ActivityDump(displays, tasks)
}

internal data class ObservedTask(
    val root: StackRoot,
    val packageName: String?,
    val packages: Set<String>,
    val activities: List<String>,
)
internal data class ComparisonRow(val grade: String, val reason: String)

internal class VdTaskObservation(private val displays: Map<Int, List<ObservedTask>>) {
    fun containsTask(id: Int) = displays.values.flatten().any { it.root.id == id || id in it.root.childIds }

    fun onDisplay(display: Int): List<ObservedTask>? = displays[display]

    fun idsForPackage(display: Int, packageName: String): Set<Int>? =
        onDisplay(display)?.filter { packageName in it.packages }?.map { it.root.id }?.toSet()

    fun comparison(main: Int, vd: Int, failureObserved: Boolean = false): Map<String, ComparisonRow> {
        val tasks = listOfNotNull(onDisplay(main), onDisplay(vd)).flatten()
        val known = onDisplay(main)?.isNotEmpty() == true && onDisplay(vd)?.isNotEmpty() == true
        fun row(grade: String, reason: String) =
            if (known) ComparisonRow(grade, reason) else ComparisonRow("missing", "Two populated displays did not reconcile")
        return linkedMapOf(
            "rootTaskId" to row("reproduced", "RootTask id joined by display and ID, separate from child taskId"),
            "topFirstOrdering" to row("reproduced", "Stack order agrees with the top-to-bottom activity hierarchy"),
            "topOrBasePackage" to row("approximated", "Root topActivity, then bottom History entry if available"),
            "packageFilteredRootIds" to row("approximated", "Top, bottom history, optional root intent, and standalone leaf alias/real hint. Exact TaskInfo fields are unavailable."),
            "pinned" to row(if (tasks.all { it.root.pinned != null }) "reproduced" else "missing", "Root configuration mWindowingMode"),
            "nullableVisibility" to row(
                if (tasks.any { it.root.visible != null }) "approximated" else "missing",
                "Child-row visibility is a proxy for the typed root field. Empty roots produce null.",
            ),
            "nullOnFailure" to if (failureObserved) {
                ComparisonRow("reproduced", "An induced nonzero shell exit produced a null observation")
            } else {
                ComparisonRow("pending", "No failed shell observation was confirmed")
            },
        )
    }
}

internal fun mergeTasks(stack: List<StackRoot>, dump: ActivityDump): VdTaskObservation {
    val proven = mutableMapOf<Int, List<ObservedTask>>()
    // An unmatched root on any display could be a task migrating between the two polls.
    for (display in dump.displays + stack.map(StackRoot::display)) {
        val roots = stack.filter { it.display == display }
        val tasks = dump.tasks.filter { it.display == display }
        if (display !in dump.displays || roots.map(StackRoot::id) != tasks.filter { it.parentId == null }.map(DumpTask::id)) {
            return VdTaskObservation(emptyMap())
        }
        proven[display] = roots.map { root ->
            val tree = mutableSetOf(root.id)
            val descendants = tasks.filter { it.id == root.id || it.parentId in tree }.onEach { tree.add(it.id) }
            val leafIds = tree - descendants.mapNotNull(DumpTask::parentId).toSet()
            if (root.childIds != leafIds && !(root.childIds.isEmpty() && tree.size == 1 && descendants.single().activities.isEmpty())) {
                return VdTaskObservation(emptyMap())
            }
            val history = descendants.flatMap(DumpTask::activities)
            val base = history.lastOrNull()?.let { component.matchEntire(it)?.groupValues?.get(1) }
            val detail = descendants.first()
            if (root.visible != null && detail.visible != null && root.visible != detail.visible) {
                return VdTaskObservation(emptyMap())
            }
            val leafHint = root.childPackages.takeIf { root.childIds == setOf(root.id) }.orEmpty()
            val packages = setOfNotNull(root.topPackage, base, detail.intentPackage) + leafHint
            ObservedTask(root, root.topPackage ?: base, packages, history)
        }
    }
    return VdTaskObservation(proven)
}

private val displayHeader = Regex("""Display: mDisplayId=(\d+)(?: \(organized\))?""")
private val focusedWindow = Regex("""mCurrentFocus=(Window\{[0-9a-fA-F]+ u\d+ .+\})""")

internal fun parseDisplayZeroFocus(result: ShellResult): String? {
    val output = result.completeOutput() ?: return null
    var display: Int? = null
    val matches = mutableListOf<String>()
    for (line in output.lineSequence().map(String::trim)) {
        if (line.startsWith("Display:")) {
            display = displayHeader.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (display == 0 && line.startsWith("mCurrentFocus=")) {
            matches += focusedWindow.matchEntire(line)?.groupValues?.get(1) ?: return null
        }
    }
    return matches.singleOrNull()
}

internal const val STACK_FAILURE_PROBE = "am stack vd_phase0_invalid_probe"

internal fun stackFailureObserved(result: ShellResult): Boolean =
    !result.truncated && result.exitCode?.let { it != 0 } == true &&
        result.stdout.contains("vd_phase0_invalid_probe") && parseStack(result) == null
