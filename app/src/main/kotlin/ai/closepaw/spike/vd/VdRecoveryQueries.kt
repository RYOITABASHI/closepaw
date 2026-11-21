package ai.closepaw.spike.vd

import ai.closepaw.adb.ShellResult

// Scan every line, including diagnostics and detail rows. De-duplicate before ADB captures stdout.
private fun recoveryQuery(command: String) = """
    set -o pipefail || exit 1
    $command 2>&1 | LC_ALL=C awk '
        function emit(row) {
            if (!(row in seen)) {
                print row
                seen[row] = 1
            }
        }
        function ids(text, pattern, kind, id) {
            while (match(text, pattern)) {
                id = substr(text, RSTART, RLENGTH)
                text = substr(text, RSTART + RLENGTH)
                sub(/^.*[^0-9]/, "", id)
                emit(kind "=" id)
            }
        }
        {
            lower = tolower($0)
            if (lower ~ /^[[:space:]]*(error|exception|security exception|permission denial|can.t find service|unknown command|dumpsys:|failure|[*]+.*dump timeout)/)
                emit("diagnostic")
            if (index($0, "closepaw_phase0")) emit("namespace")
            if (index(lower, "$FIXTURE_PACKAGE")) emit("fixture")
            ids(lower, "(^|[^[:alnum:]_])logicaldisplay[[]id[[:space:]]*=[[:space:]]*[0-9]+", "display")
            ids(lower, "(^|[^[:alnum:]_])(m?display|logical[[:space:]_-]*display)([[:space:]_-]*id)?([[:space:]:=#(]|[[]|[{])*[0-9]+", "display")
            ids(lower, "(^|[^[:alnum:]_])(m?task|root[[:space:]_-]*task)([[:space:]_-]*id)?[[:space:]:=#]*[0-9]+", "task")
            ids(lower, "(^|[^[:alnum:]_])task(record)?[[:space:]]*[{][^}#]*#[[:space:]]*[0-9]+", "task")
            ids(lower, "(^|[^[:alnum:]_])task[[:space:]]+[0-9]+:[^[:space:]]+[[:space:]]+id[[:space:]=:#]*[0-9]+", "task")
            if (lower ~ /^[[:space:]]*logical displays:[[:space:]]*size[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$/)
                emit("logicalDisplays")
            if (lower ~ /^[[:space:]]*display devices:/)
                emit("displayDevices")
            ids(lower, "(^|[^[:alnum:]_])t[[:space:]]*[0-9]+", "task")
        }
        END { print "complete" }
    '
""".trimIndent()

internal val recoveryDisplayQuery = recoveryQuery("dumpsys display")
internal val recoveryActivitiesQuery = recoveryQuery("dumpsys activity activities")
internal val recoveryStackQuery = recoveryQuery("am stack list")

internal data class RecoveryCandidates(
    val displayIds: Set<Int>,
    val taskIds: Set<Int>,
    val namespacePresent: Boolean,
    val fixturePresent: Boolean,
)

private val candidateId = Regex("""(display|task)=([0-9]+)""")

internal fun parseRecoveryDisplay(result: ShellResult): RecoveryCandidates? =
    parseRecoveryCandidates(result, requireDisplaySections = true)

internal fun parseRecoveryCandidates(
    result: ShellResult,
    requireDisplaySections: Boolean = false,
): RecoveryCandidates? {
    val lines = result.completeOutput()?.lineSequence()?.filter(String::isNotEmpty)?.toList() ?: return null
    if (lines.lastOrNull() != "complete") return null
    val displays = mutableSetOf<Int>()
    val tasks = mutableSetOf<Int>()
    var logicalDisplays = false
    var displayDevices = false
    var namespace = false
    var fixture = false
    for (line in lines.dropLast(1)) {
        when (line) {
            "logicalDisplays" -> logicalDisplays = true
            "displayDevices" -> displayDevices = true
            "namespace" -> namespace = true
            "fixture" -> fixture = true
            else -> {
                val (kind, digits) = candidateId.matchEntire(line)?.destructured ?: return null
                val id = digits.toIntOrNull() ?: return null
                when (kind) {
                    "display" -> displays.add(id)
                    "task" -> tasks.add(id)
                }
            }
        }
    }
    if (0 !in displays || (requireDisplaySections && !(logicalDisplays && displayDevices))) return null
    return RecoveryCandidates(displays, tasks, namespace, fixture)
}

// pidof reports absence as status 1 with empty output. Any other failure remains unverified.
internal fun parseFixturePidAbsence(result: ShellResult): Boolean? = when {
    result.truncated -> null
    result.exitCode == 1 && result.stdout.isBlank() -> true
    result.completeOutput()?.trim()?.matches(Regex("""[1-9]\d*(?:\s+[1-9]\d*)*""")) == true -> false
    else -> null
}
