package ai.closepaw.spike.vd

import kotlinx.serialization.json.*

internal enum class VdStage(val wire: String) {
    PROOF_1("proof1"), PROOF_2("proof2"), PROOF_3("proof3"), PROOF_4("proof4"), CLEANUP("cleanup")
}

internal enum class VdOutcome(val wire: String) {
    PASSED("passed"), FAILED("failed"), SKIPPED("skipped")
}

internal data class VdStageRecord(
    val stage: VdStage,
    val outcome: VdOutcome,
    val elapsedMs: Long,
    val evidence: JsonObject,
)

internal data class VdStageLog(
    val runId: String,
    val stages: List<VdStageRecord> = emptyList(),
    val journal: VdJournal? = null,
    val recovery: JsonObject? = null,
    val endpointDeaths: JsonObject? = null,
) {
    val rerunAllowed get() = recoveryConfirmed(recovery, journal) ||
        stages.lastOrNull()?.let { it.stage == VdStage.CLEANUP && it.outcome == VdOutcome.PASSED } == true

    val p1EndpointDeathsPassed get() = listOf("helperOnly", "appOnly").all {
        (endpointDeaths?.get(it) as? JsonObject)?.get("passed") == JsonPrimitive(true)
    }

    val p1Passed: Boolean
        get() {
            val fixture = passedEvidence(VdStage.PROOF_1)
            val release = passedEvidence(VdStage.PROOF_4)
            return fixture?.get("capturedFixtureFrame") is JsonObject &&
                fixture["accessibilityRoot"] is JsonObject &&
                release?.get("liveHelperReleaseAcknowledged") == JsonPrimitive(true) &&
                p1EndpointDeathsPassed
        }

    val terminalSuccess get() = p1Passed && stages.size == VdStage.entries.size &&
        stages.all { it.outcome == VdOutcome.PASSED }

    private fun passedEvidence(stage: VdStage): JsonObject? =
        stages.getOrNull(stage.ordinal)?.takeIf { it.stage == stage && it.outcome == VdOutcome.PASSED }?.evidence

    fun next(
        stage: VdStage,
        outcome: VdOutcome,
        elapsedMs: Long,
        evidence: JsonObject = buildJsonObject {},
    ): VdStageLog {
        require(elapsedMs >= 0 && VdStage.entries.getOrNull(stages.size) == stage)
        if (stage == VdStage.CLEANUP) {
            require(outcome != VdOutcome.SKIPPED)
        } else {
            require((outcome == VdOutcome.SKIPPED) == stages.any { it.outcome == VdOutcome.FAILED })
        }
        return copy(stages = stages + VdStageRecord(stage, outcome, elapsedMs, evidence))
    }

    fun json() = buildJsonObject {
        put("runId", runId)
        journal?.let { put("journal", it.json()) }
        recovery?.let { put("recovery", it) }
        endpointDeaths?.let { put("endpointDeaths", it) }
        put("stages", buildJsonArray {
            stages.forEach { record ->
                add(buildJsonObject {
                    put("stage", record.stage.wire)
                    put("outcome", record.outcome.wire)
                    put("elapsedMs", record.elapsedMs)
                    put("evidence", record.evidence)
                })
            }
        })
    }
}

internal sealed interface VdStageLoad {
    data object Missing : VdStageLoad
    data object Invalid : VdStageLoad
    data class Present(val log: VdStageLog) : VdStageLoad
}

internal fun decodeVdStages(raw: String?): VdStageLoad {
    if (raw == null) return VdStageLoad.Missing
    return try {
        val root = Json.parseToJsonElement(raw).jsonObject
        val runId = root.getValue("runId").jsonPrimitive
        require(runId.isString && runId.content.isNotBlank())
        val log = root.getValue("stages").jsonArray.fold(VdStageLog(
            runId.content,
            journal = root["journal"]?.jsonObject?.let(VdJournal::decode),
            recovery = root["recovery"]?.jsonObject,
            endpointDeaths = root["endpointDeaths"]?.jsonObject,
        )) { previous, item ->
            val row = item.jsonObject
            val stage = VdStage.entries.single { it.wire == row.getValue("stage").jsonPrimitive.content }
            val outcome = VdOutcome.entries.single { it.wire == row.getValue("outcome").jsonPrimitive.content }
            val elapsed = row.getValue("elapsedMs").jsonPrimitive
            require(!elapsed.isString)
            previous.next(stage, outcome, elapsed.long, row.getValue("evidence").jsonObject)
        }
        // Require exactly the supported schema, including JSON value types.
        require(log.json() == root)
        VdStageLoad.Present(log)
    } catch (_: Exception) {
        VdStageLoad.Invalid
    }
}
