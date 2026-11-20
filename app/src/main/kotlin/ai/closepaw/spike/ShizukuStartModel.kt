package ai.closepaw.spike

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/*
 * SPIKE (throwaway, never merges): pure, JVM-testable parts of the Shizuku-start probe.
 * See ShizukuStartSpike.kt for the device flow.
 */

/**
 * Validated `spike_shizuku_start` arguments. [pairCode] is a secret: it must never reach a stage
 * record, a log line, or [toString].
 */
internal class ShizukuStartArgs(val pairPort: Int?, val pairCode: String?, val connectPort: Int) {
    val hasPair: Boolean get() = pairPort != null && pairCode != null

    override fun toString(): String = "ShizukuStartArgs(pairPort=$pairPort, pairCode=<redacted>, connectPort=$connectPort)"

    companion object {
        private val PAIR_CODE_RE = Regex("^[0-9]{6}$")

        /** Left = validated args, right = error message (never echoes the pair code). */
        fun parse(args: JsonObject): Pair<ShizukuStartArgs?, String?> {
            val connectPort = port(args["connectPort"]) ?: return null to "connectPort must be an integer 1..65535"
            val pairPortRaw = args["pairPort"]
            val pairCodeRaw = args["pairCode"]
            val pairPort = if (pairPortRaw == null || pairPortRaw is JsonNull) null
            else port(pairPortRaw) ?: return null to "pairPort must be an integer 1..65535"
            val pairCode = if (pairCodeRaw == null || pairCodeRaw is JsonNull) null
            else (pairCodeRaw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { PAIR_CODE_RE.matches(it) }
                ?: return null to "pairCode must be a 6-digit string"
            if ((pairPort == null) != (pairCode == null)) {
                return null to "pairPort and pairCode must be given together"
            }
            return ShizukuStartArgs(pairPort, pairCode, connectPort) to null
        }

        private fun port(el: Any?): Int? {
            val p = el as? JsonPrimitive ?: return null
            // Accept 37000 and "37000"; reject 37000.5 / "abc".
            val v = if (p.isString) p.content.trim().toIntOrNull() else p.intOrNull
            return v?.takeIf { it in 1..65535 }
        }
    }
}

/** Shell plumbing for the probe: the starter path, the exit-code marker, output caps. */
internal object ShizukuStartShell {
    const val STARTER_PATH = "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
    const val CHECK_OUTPUT_CHARS = 500
    const val RUN_OUTPUT_CHARS = 1_000

    /**
     * The adb `shell:` service (v1, no shell_v2) does not report an exit status, so every command
     * gets `; echo <marker>$?` appended and the marker line is stripped again by [parse].
     */
    const val RC_MARKER = "__closepaw_rc="

    val CHECK_COMMAND = "ls $STARTER_PATH"
    val RUN_COMMAND = "sh $STARTER_PATH"

    fun withExitCode(cmd: String): String = "$cmd 2>&1; echo $RC_MARKER$?"

    /** Output with the marker line removed, plus the exit code (null if the marker never arrived). */
    data class Result(val output: String, val exitCode: Int?)

    fun parse(raw: String): Result {
        val idx = raw.lastIndexOf(RC_MARKER)
        if (idx < 0) return Result(raw.trimEnd(), null)
        val rc = raw.substring(idx + RC_MARKER.length).trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()
        return Result(raw.substring(0, idx).trimEnd(), rc)
    }

    fun starterExists(r: Result): Boolean =
        r.exitCode == 0 && r.output.lineSequence().any { it.trim() == STARTER_PATH }
}

/** The `spikeStart` field of closepaw_status. */
internal object ShizukuStartStatus {
    fun json(records: List<SpikeStageRecord>, running: Boolean): JsonObject = buildJsonObject {
        put("running", JsonPrimitive(running))
        put("current", records.lastOrNull()?.toJson() ?: JsonNull)
        put("stages", buildJsonArray { records.forEach { add(it.toJson()) } })
    }

    /** `current.stage` of a [json] payload, for the audit outcome. */
    fun currentStage(status: JsonObject): String? =
        ((status["current"] as? JsonObject)?.get("stage") as? JsonPrimitive)?.contentOrNull
}
