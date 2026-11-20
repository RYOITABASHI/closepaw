package ai.closepaw.spike.vd

import ai.closepaw.adb.ShellResult
import kotlinx.serialization.json.*

internal const val FIXTURE_PACKAGE = "ai.closepaw.vdprobe"
internal const val FIXTURE_COMPONENT = "$FIXTURE_PACKAGE/.ProbeActivity"

private val taskHeader = Regex("""TASK .* id=(\d+) userId=\d+ displayId=(\d+)(?:\(.*\))?""")

private val fixtureLine = Regex(
    """VD_PHASE0 run=([a-f0-9-]+) role=(main|vd|owned) instance=([a-f0-9-]+) """ +
        """display=(-?\d+) task=(\d+) attached=(true|false) drawn=(true|false) focused=(true|false) """ +
        """x=(-?\d+) y=(-?\d+) taps=(\d+) keys=(\d+) text=([a-zA-Z0-9_]*)"""
)

internal data class FixtureWindow(
    val runId: String,
    val role: String,
    val instance: String,
    val display: Int,
    val task: Int,
    val attached: Boolean,
    val drawn: Boolean,
    val focused: Boolean,
    val x: Int,
    val y: Int,
    val taps: Int,
    val keys: Int,
    val text: String,
) {
    fun json() = buildJsonObject {
        put("role", role)
        put("displayId", display)
        put("taskId", task)
        put("instance", instance)
        put("attached", attached)
        put("drawn", drawn)
        put("focused", focused)
        put("tapX", x)
        put("tapY", y)
        put("taps", taps)
        put("keys", keys)
        put("text", text)
    }

    fun sameInputAs(other: FixtureWindow) =
        instance == other.instance && display == other.display && task == other.task &&
            taps == other.taps && keys == other.keys && text == other.text
}

internal fun parseFixture(result: ShellResult): List<FixtureWindow>? {
    val output = result.completeOutput() ?: return null
    val records = mutableListOf<FixtureWindow>()
    var task: Int? = null
    var display: Int? = null
    for (line in output.lineSequence().map(String::trim)) {
        if (line.startsWith("TASK ")) {
            val header = taskHeader.matchEntire(line)?.groupValues
            task = header?.get(1)?.toIntOrNull()
            display = header?.get(2)?.toIntOrNull()
        }
        if (!line.startsWith("VD_PHASE0 ")) continue
        val fields = fixtureLine.matchEntire(line)?.groupValues ?: return null
        val numbers = (listOf(4, 5, 9, 10, 11, 12)).map { fields[it].toIntOrNull() ?: return null }
        if (numbers[0] != display || numbers[1] != task) return null
        records.add(FixtureWindow(
            fields[1], fields[2], fields[3], numbers[0], numbers[1],
            fields[6].toBooleanStrict(), fields[7].toBooleanStrict(), fields[8].toBooleanStrict(),
            numbers[2], numbers[3], numbers[4], numbers[5], fields[13],
        ))
    }
    return records.takeIf { it.map(FixtureWindow::instance).distinct().size == it.size }
}
