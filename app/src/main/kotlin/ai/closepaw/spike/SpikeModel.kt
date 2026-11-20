package ai.closepaw.spike

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * SPIKE (throwaway, never merges): pure, JVM-testable parts of the self-reinstall probe.
 * See SelfUpdateSpike.kt for the device flow.
 */

/** Where this build's own release APK lives, derived from its versionName. */
internal data class SpikeAsset(val runNumber: Int, val tag: String, val fileName: String, val url: String) {
    companion object {
        /** CI stamps `-PVERSION_NAME=<base>-shelly.<run_number>` (build-shelly-variant.yml). */
        private val VERSION_RE = Regex("""^(\d+\.\d+\.\d+)-shelly\.(\d+)$""")
        private const val RELEASES = "https://github.com/RYOITABASHI/closepaw/releases/download"

        /** Null when [versionName] is not a Shelly-variant CI build (e.g. a local debug build). */
        fun fromVersionName(versionName: String?): SpikeAsset? {
            val m = VERSION_RE.matchEntire(versionName?.trim() ?: return null) ?: return null
            val base = m.groupValues[1]
            val run = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val tag = "shelly-v$base-$run"
            val file = "ClosePaw-shelly-$run.apk"
            return SpikeAsset(run, tag, file, "$RELEASES/$tag/$file")
        }
    }
}

/** Which hosts a download redirect may land on (https only). */
internal object SpikeRedirectPolicy {
    private const val EXACT = "github.com"
    private const val SUFFIX = ".githubusercontent.com" // objects./release-assets. etc.

    fun isAllowed(protocol: String?, host: String?): Boolean {
        if (protocol != "https") return false
        val h = host?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return h == EXACT || (h.endsWith(SUFFIX) && h.length > SUFFIX.length)
    }
}

/**
 * One stage record: `{stage, atMs, ...fields}`. Fields are flat so the status payload reads
 * naturally (`{"stage":"downloaded","atMs":…,"bytes":…,"sha256":…}`).
 */
internal data class SpikeStageRecord(
    val stage: String,
    val atMs: Long,
    val fields: Map<String, JsonPrimitive> = emptyMap(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("stage", JsonPrimitive(stage))
        put("atMs", JsonPrimitive(atMs))
        fields.forEach { (k, v) -> if (k != "stage" && k != "atMs") put(k, v) }
    }

    companion object {
        fun fromJson(el: JsonElement): SpikeStageRecord? {
            val o = el as? JsonObject ?: return null
            val stage = (o["stage"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            val atMs = (o["atMs"] as? JsonPrimitive)?.longOrNull ?: return null
            val fields = o.filterKeys { it != "stage" && it != "atMs" }
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it } }
                .toMap()
            return SpikeStageRecord(stage, atMs, fields)
        }
    }
}

/** The stage file: an append-only (capped) list of records; the last one is the current stage. */
internal object SpikeStageLog {
    const val MAX_RECORDS = 50
    const val MAX_FIELD_CHARS = 2_000
    private val JSON = Json { ignoreUnknownKeys = true }

    fun encode(records: List<SpikeStageRecord>): String =
        buildJsonArray { records.takeLast(MAX_RECORDS).forEach { add(it.toJson()) } }.toString()

    /** Tolerant: unreadable content yields an empty log rather than an exception. */
    fun decode(text: String?): List<SpikeStageRecord> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { SpikeStageRecord.fromJson(it) }
    }

    fun append(text: String?, record: SpikeStageRecord): String = encode(decode(text) + record)

    /** Last stage that was reached (ignores the terminal "failed" record itself). */
    fun lastCompletedStage(records: List<SpikeStageRecord>): String =
        records.lastOrNull { it.stage != "failed" }?.stage ?: "none"

    fun field(value: String?): JsonPrimitive = JsonPrimitive(value?.take(MAX_FIELD_CHARS))
    fun field(value: Number): JsonPrimitive = JsonPrimitive(value)

    /** `"<exception class>: <message>"`, unwrapping reflection's InvocationTargetException. */
    fun describe(t: Throwable): String {
        var cause: Throwable = t
        while (cause is java.lang.reflect.InvocationTargetException && cause.targetException != null) {
            cause = cause.targetException
        }
        return "${cause.javaClass.name}: ${cause.message}".take(MAX_FIELD_CHARS)
    }

    /** The `spike` field of closepaw_status. */
    fun statusJson(records: List<SpikeStageRecord>, packageLastUpdateTime: Long?, running: Boolean): JsonObject =
        buildJsonObject {
            put("running", JsonPrimitive(running))
            put("current", records.lastOrNull()?.toJson() ?: JsonNull)
            put("stages", buildJsonArray { records.forEach { add(it.toJson()) } })
            put("packageLastUpdateTime", packageLastUpdateTime?.let { JsonPrimitive(it) } ?: JsonNull)
        }
}
