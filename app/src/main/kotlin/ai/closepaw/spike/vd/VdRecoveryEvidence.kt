package ai.closepaw.spike.vd

import ai.closepaw.adb.ShellResult
import kotlinx.serialization.json.*

internal val stagingPath = Regex("/data/local/tmp/closepaw-vd-[0-9a-f]{24}")
internal val helperName = Regex("closepaw-vd-[0-9a-f]{24}")

internal data class VdJournal(
    val stagingDir: String? = null,
    val helperPid: Int? = null,
    val displayId: Int? = null,
    val ownedTaskIds: Set<Int> = emptySet(),
    val appDeathArmedBy: String? = null,
    val baselineDisplayIds: Set<Int> = emptySet(),
) {
    init {
        require(stagingDir == null || stagingPath.matches(stagingDir))
        require(helperPid == null || helperPid > 0)
        require(displayId == null || displayId > 0)
        require(ownedTaskIds.all { it > 0 })
        require(baselineDisplayIds.all { it >= 0 })
    }

    fun json() = buildJsonObject {
        put("stagingDir", stagingDir)
        put("helperPid", helperPid?.let(::JsonPrimitive) ?: JsonNull)
        put("displayId", displayId?.let(::JsonPrimitive) ?: JsonNull)
        put("ownedTaskIds", buildJsonArray { ownedTaskIds.sorted().forEach { add(JsonPrimitive(it)) } })
        put("appDeathArmedBy", appDeathArmedBy)
        if (baselineDisplayIds.isNotEmpty()) {
            put("baselineDisplayIds", buildJsonArray { baselineDisplayIds.sorted().forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        fun decode(row: JsonObject) = VdJournal(
            row.getValue("stagingDir").jsonPrimitive.contentOrNull,
            row.getValue("helperPid").jsonPrimitive.intOrNull,
            row.getValue("displayId").jsonPrimitive.intOrNull,
            row.getValue("ownedTaskIds").jsonArray.map { it.jsonPrimitive.int }.toSet(),
            row.getValue("appDeathArmedBy").jsonPrimitive.contentOrNull,
            row["baselineDisplayIds"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet().orEmpty(),
        )
    }
}

internal data class VdRecoveryEvidence(
    val items: Map<String, Boolean>,
    val diagnostics: Map<String, JsonPrimitive> = emptyMap(),
) {
    val confirmed get() = items.isNotEmpty() && items.values.all { it }

    fun json() = buildJsonObject {
        put("stage", "recovery")
        put("outcome", if (confirmed) "passed" else "failed")
        put("confirmed", confirmed)
        put("items", buildJsonObject {
            items.forEach { (item, gone) -> put(item, gone) }
            diagnostics.forEach { (name, value) -> put(name, value) }
        })
    }
}

internal fun recoveryConfirmed(record: JsonObject?, journal: VdJournal?): Boolean {
    val items = record?.get("items") as? JsonObject ?: return false
    val required = recoveryItemNames(journal ?: return false)
    return record["stage"] == JsonPrimitive("recovery") && record["outcome"] == JsonPrimitive("passed") &&
        record["confirmed"] == JsonPrimitive(true) && items.keys.containsAll(required) &&
        items.filterKeys { !it.startsWith("diag:") }.values.all { it == JsonPrimitive(true) }
}

internal fun shellEvidence(name: String, result: ShellResult?, parsed: Boolean): Map<String, JsonPrimitive> =
    mapOf(
        "diag:${name}ExitCode" to JsonPrimitive(result?.exitCode),
        "diag:${name}Truncated" to JsonPrimitive(result?.truncated),
        "diag:${name}StdoutBytes" to JsonPrimitive(result?.stdout?.toByteArray(Charsets.UTF_8)?.size),
        "diag:${name}CompleteOutputNull" to JsonPrimitive(result?.completeOutput() == null),
        "diag:${name}Parsed" to JsonPrimitive(parsed),
    )

internal fun parseRecoveryProcesses(result: ShellResult): Map<Int, String>? {
    val lines = result.completeOutput()?.trim()?.lines() ?: return null
    if (lines.firstOrNull()?.trim()?.split(Regex("\\s+")) != listOf("PID", "NAME") || lines.size < 2) return null
    val processes = mutableMapOf<Int, String>()
    for (line in lines.drop(1)) {
        val fields = line.trim().split(Regex("\\s+"), limit = 2)
        val pid = fields.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val name = fields.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        if (processes.put(pid, name) != null) return null
    }
    return processes
}

internal fun recoveryItemNames(journal: VdJournal): Set<String> = buildSet {
    addAll(listOf("helperNamespace", "displayNamespace", "stagingNamespace", "fixturePackage"))
    journal.helperPid?.let { add("helperPid:$it") }
    journal.displayId?.let { add("displayId:$it") }
    journal.stagingDir?.let { add("stagingDir:$it") }
    journal.ownedTaskIds.forEach { add("taskId:$it") }
}
