package ai.closepaw.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Where this build's own release APK lives, derived from its versionName. */
internal data class UpdateAsset(val runNumber: Int, val tag: String, val fileName: String, val url: String) {
    companion object {
        /** CI stamps `-PVERSION_NAME=<base>-shelly.<run_number>` (build-shelly-variant.yml). */
        private val VERSION_RE = Regex("""^(\d+\.\d+\.\d+)-shelly\.(\d+)$""")
        private const val RELEASES = "https://github.com/RYOITABASHI/closepaw/releases/download"

        /** Null when [versionName] is not a Shelly-variant CI build (e.g. a local debug build). */
        fun fromVersionName(versionName: String?): UpdateAsset? {
            val m = VERSION_RE.matchEntire(versionName?.trim() ?: return null) ?: return null
            val base = m.groupValues[1]
            val run = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val tag = "shelly-v$base-$run"
            val file = "ClosePaw-shelly-$run.apk"
            return UpdateAsset(run, tag, file, "$RELEASES/$tag/$file")
        }
    }
}

/** Which hosts a download redirect may land on (https only). */
internal object UpdateRedirectPolicy {
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
internal data class UpdateStageRecord(
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
        private val knownStages = setOf(
            "started", "aborted", "downloaded", "session-created", "written", "commit-sent",
            "pending-user-action", "abandoned", "abandon-failed", "failed", "staged",
            "launch-acknowledged", "launch-unknown", "install-output", "result",
        )

        fun fromJson(el: JsonElement): UpdateStageRecord? {
            val o = el as? JsonObject ?: return null
            val stage = (o["stage"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            if (stage !in knownStages) return null
            val atMs = (o["atMs"] as? JsonPrimitive)?.longOrNull ?: return null
            val fields = o.filterKeys { it != "stage" && it != "atMs" }
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { k to it } }
                .toMap()
            return UpdateStageRecord(stage, atMs, fields)
        }
    }
}

/** The stage file: an append-only (capped) list of records; the last one is the current stage. */
internal object UpdateStageLog {
    const val MAX_RECORDS = 50
    const val MAX_FIELD_CHARS = 2_000
    private val JSON = Json { ignoreUnknownKeys = true }

    fun encode(records: List<UpdateStageRecord>): String =
        buildJsonArray { records.takeLast(MAX_RECORDS).forEach { add(it.toJson()) } }.toString()

    fun writeVerified(
        content: ByteArray,
        stream: FileOutputStream,
        finishWrite: (FileOutputStream) -> Unit,
        failWrite: (FileOutputStream) -> Unit,
        readCommitted: () -> ByteArray,
    ) {
        try {
            stream.write(content)
            stream.fd.sync()
            finishWrite(stream)
        } catch (e: Exception) {
            failWrite(stream)
            throw e
        }
        // AtomicFile.finishWrite can log a rename failure without throwing.
        check(readCommitted().contentEquals(content)) { "Stage write could not be verified" }
    }

    fun decode(text: String): List<UpdateStageRecord> {
        val arr = JSON.parseToJsonElement(text) as? JsonArray
            ?: throw IllegalArgumentException("Expected an array of update stages")
        val records = arr.map {
            requireNotNull(UpdateStageRecord.fromJson(it)) { "Invalid update stage" }
        }
        val current = records.lastOrNull { it.stage != "pending-user-action" }
        if (current?.stage == "commit-sent" || records.lastOrNull()?.stage == "pending-user-action") {
            require(ShizukuUpdateSession.pending(records) != null) { "Invalid Shizuku session" }
        }
        return records
    }

    /** Missing stores have no history. All other read or decode failures leave it unknown. */
    fun read(file: File, key: String? = null): List<UpdateStageRecord> {
        try {
            // Honor AtomicFile's legacy backup precedence without renaming or deleting
            // recovery files during a read. An unfinished write is never committed history.
            val backup = File("$file.bak").toPath()
            val path = file.toPath()
            val bytes = try {
                Files.readAllBytes(backup)
            } catch (e: NoSuchFileException) {
                if (!Files.notExists(backup, LinkOption.NOFOLLOW_LINKS)) throw e
                try {
                    Files.readAllBytes(path)
                } catch (missing: NoSuchFileException) {
                    val unfinished = File("$file.new").toPath()
                    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS) &&
                        Files.notExists(unfinished, LinkOption.NOFOLLOW_LINKS)
                    ) return emptyList()
                    throw missing
                }
            }
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            if (key == null) return decode(text)

            val container = JSON.parseToJsonElement(text) as? JsonObject
            val stages = requireNotNull(container?.get(key)) { "Missing $key stage log" }
            val records = decode(stages.toString())
            val latest = records.lastOrNull()
            if (latest?.stage in setOf("staged", "launch-acknowledged", "launch-unknown")) {
                require(AdbUpdateStages.pendingTicket(latest) != null) { "Invalid ADB install ticket" }
            }
            return records
        } catch (e: IOException) {
            throw UnreadableUpdateStageStore(file, e)
        } catch (e: IllegalArgumentException) {
            throw UnreadableUpdateStageStore(file, e)
        } catch (e: SecurityException) {
            throw UnreadableUpdateStageStore(file, e)
        }
    }

    fun readStatus(
        packageLastUpdateTime: Long?,
        running: Boolean,
        read: () -> JsonObject,
    ): JsonObject = try {
        read()
    } catch (e: UnreadableUpdateStageStore) {
        buildJsonObject {
            put("running", JsonPrimitive(running))
            put("current", JsonNull)
            put("stages", JsonNull)
            put("packageLastUpdateTime", packageLastUpdateTime?.let { JsonPrimitive(it) } ?: JsonNull)
            put("stageStore", JsonPrimitive("unreadable"))
            put("error", JsonPrimitive(e.message))
        }
    }

    /** Last stage that was reached (ignores the terminal "failed" record itself). */
    fun lastCompletedStage(records: List<UpdateStageRecord>): String =
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

    fun statusJson(records: List<UpdateStageRecord>, packageLastUpdateTime: Long?, running: Boolean): JsonObject =
        buildJsonObject {
            put("running", JsonPrimitive(running))
            put("current", records.lastOrNull()?.toJson() ?: JsonNull)
            put("stages", buildJsonArray { records.forEach { add(it.toJson()) } })
            put("packageLastUpdateTime", packageLastUpdateTime?.let { JsonPrimitive(it) } ?: JsonNull)
        }
}

internal class UnreadableUpdateStageStore(file: File, cause: Exception) :
    IOException("Stage store is unreadable: ${file.name}", cause)

internal data class UpdateStartDecision(val accepted: Boolean, val error: String? = null)

internal fun startUpdateIfReady(
    readShizuku: () -> List<UpdateStageRecord>,
    hasAdbTicket: () -> Boolean,
    handlePendingShizuku: (ShizukuUpdateSession) -> Unit,
    launch: (recoverAdb: Boolean) -> Unit,
): UpdateStartDecision {
    val shizuku: ShizukuUpdateSession?
    val recoverAdb: Boolean
    try {
        shizuku = ShizukuUpdateSession.pending(readShizuku())
        recoverAdb = hasAdbTicket()
    } catch (e: UnreadableUpdateStageStore) {
        return UpdateStartDecision(false, e.message)
    }
    if (shizuku != null) {
        handlePendingShizuku(shizuku)
        return UpdateStartDecision(false)
    }
    launch(recoverAdb)
    return UpdateStartDecision(true)
}

internal data class ShizukuUpdateSession(val id: Int, val deadlineAtMs: Long) {
    fun expired(now: Long): UpdateStageRecord? {
        if (now < deadlineAtMs) return null
        return UpdateStageRecord("result", now, mapOf(
            "sessionId" to JsonPrimitive(id),
            "outcome" to JsonPrimitive("unknown"),
            "reason" to JsonPrimitive("Install callback timed out"),
        ))
    }

    companion object {
        fun pending(records: List<UpdateStageRecord>): ShizukuUpdateSession? {
            val commit = records.lastOrNull { it.stage != "pending-user-action" }
                ?.takeIf { it.stage == "commit-sent" } ?: return null
            val id = commit.fields["sessionId"]?.intOrNull?.takeIf { it >= 0 } ?: return null
            val deadline = commit.fields["deadlineAtMs"]?.longOrNull ?: return null
            return ShizukuUpdateSession(id, deadline)
        }
    }
}

internal object AdbUpdateStages {
    fun started(atMs: Long) = UpdateStageRecord("started", atMs)

    fun staged(atMs: Long, bytes: Long, ticket: String) =
        UpdateStageRecord("staged", atMs, mapOf("bytes" to JsonPrimitive(bytes), "ticket" to JsonPrimitive(ticket)))

    private val ticketPattern = Regex("[a-f0-9-]{36}")
    private val exitMarker = Regex("""\r?\n__rc=(\d{1,3})\r?\n\z""")

    fun pendingTicket(stage: UpdateStageRecord?): String? =
        stage?.takeIf { it.stage in setOf("staged", "launch-acknowledged", "launch-unknown") }
            ?.fields?.get("ticket")?.content?.takeIf(ticketPattern::matches)

    fun launch(atMs: Long, staged: UpdateStageRecord, exitCode: Int?, detail: String) =
        UpdateStageRecord(if (exitCode == 0) "launch-acknowledged" else "launch-unknown", atMs, mapOf(
            "ticket" to staged.fields.getValue("ticket"),
            "stagedAtMs" to JsonPrimitive(staged.atMs),
            "exitCode" to JsonPrimitive(exitCode),
            "detail" to UpdateStageLog.field(detail),
        ))

    fun completed(atMs: Long, payload: String): List<UpdateStageRecord>? {
        val marker = exitMarker.find(payload) ?: return null
        val output = payload.substring(0, marker.range.first)
        val exitCode = marker.groupValues[1].toInt()
        return listOf(
            UpdateStageRecord("install-output", atMs, mapOf("output" to UpdateStageLog.field(output))),
            UpdateStageRecord("result", atMs, mapOf(
                "success" to JsonPrimitive(exitCode == 0 && output.lineSequence().any { it == "Success" }),
                "exitCode" to JsonPrimitive(exitCode),
            )),
        )
    }

    fun expired(atMs: Long) = UpdateStageRecord("result", atMs, mapOf(
        "outcome" to JsonPrimitive("unknown"),
        "reason" to JsonPrimitive("Installer result unverifiable before deadline"),
    ))

    fun failed(atMs: Long, error: Exception) = UpdateStageRecord("result", atMs, mapOf(
        "success" to JsonPrimitive(false),
        "error" to JsonPrimitive(error.javaClass.simpleName),
    ))
}

private const val ADB_RESULT_WINDOW_MS = 180_000L

/** Bounds the whole recovery loop, including connection attempts, reads, and retry delays. */
internal suspend fun awaitAdbUpdateResult(
    stage: UpdateStageRecord,
    now: () -> Long,
    pause: suspend (Long) -> Unit = { delay(it) },
    read: suspend () -> String?,
): List<UpdateStageRecord> {
    val deadline = (stage.fields["stagedAtMs"]?.longOrNull ?: stage.atMs) + ADB_RESULT_WINDOW_MS
    val remaining = (deadline - now()).coerceIn(0L, ADB_RESULT_WINDOW_MS)
    return withTimeoutOrNull(remaining) {
        while (now() < deadline) {
            val payload = try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val atMs = now()
            if (atMs >= deadline) break
            if (payload != null) {
                AdbUpdateStages.completed(atMs, payload)?.let { return@withTimeoutOrNull it }
            }
            pause(minOf(1_000L, deadline - atMs))
        }
        null
    } ?: listOf(AdbUpdateStages.expired(now()))
}
