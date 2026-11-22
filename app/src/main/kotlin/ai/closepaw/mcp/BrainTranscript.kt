package ai.closepaw.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BrainTranscript {
    const val TYPE_SESSION_STARTED = "brain_session_started"
    const val TYPE_OBSERVATION = "observation"
    const val TYPE_APPROVAL = "approval"
    const val TYPE_GUARD_VERDICT = "guard_verdict"
    const val TYPE_ACTION = "brain_action"
    const val TYPE_SESSION_FINISHED = "brain_session_finished"

    const val RETENTION_KEEP = 50

    /** Pass a precomputed goal fingerprint and the presence flags for the correlation secrets. */
    fun sessionStarted(
        goalFingerprint: String,
        origin: String,
        reqIdPresent: Boolean,
        noncePresent: Boolean,
    ): JsonObject = buildJsonObject {
        put("goalFingerprint", goalFingerprint)
        put("origin", origin)
        put("hasReqId", reqIdPresent)
        put("hasNonce", noncePresent)
        put("maxActions", BrainSessionLimits.MAX_ACTIONS_PER_SESSION)
        put("wallClockMs", BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS)
        put("loopWindow", BrainSessionLimits.LOOP_WINDOW)
        put("loopSimilarity", BrainSessionLimits.LOOP_SIMILARITY_THRESHOLD)
    }

    fun observation(
        packageName: String?,
        elementCount: Int,
        masked: Boolean,
        partiallyMasked: Boolean,
        imageArtifactPath: String?,
        imageTaken: Boolean,
        atMs: Long,
    ): JsonObject = buildJsonObject {
        if (packageName != null) put("packageName", packageName)
        put("elementCount", elementCount)
        put("masked", masked)
        put("partiallyMasked", partiallyMasked)
        if (imageArtifactPath != null) put("imageArtifactPath", imageArtifactPath)
        put("imageTaken", imageTaken)
        put("atMs", atMs)
    }

    fun guardVerdict(trip: String, actionsExecuted: Int, atMs: Long): JsonObject = buildJsonObject {
        put("trip", trip)
        put("actionsExecuted", actionsExecuted)
        put("atMs", atMs)
    }

    /** Pass an approval phase of `entered` or `resolved` and a precomputed call ID fingerprint. */
    fun approval(
        phase: String,
        callIdFingerprint: String,
        packageName: String?,
        atMs: Long,
    ): JsonObject = buildJsonObject {
        put("phase", phase)
        put("callIdFingerprint", callIdFingerprint)
        if (packageName != null) put("packageName", packageName)
        put("atMs", atMs)
    }

    /** Never pass raw goal or typed text in any field. For type_text, targetKey is a fingerprint. */
    fun action(
        record: BrainActionRecord,
        outcome: String,
        verified: Boolean?,
        guardVerdict: String? = null,
        changed: Boolean? = null,
        postPackageName: String? = null,
    ): JsonObject = buildJsonObject {
        put("tool", record.tool)
        put("targetKey", record.targetKey)
        put("outcome", outcome)
        put("atMs", record.atMs)
        if (verified != null) put("verified", verified)
        if (guardVerdict != null) put("guardVerdict", guardVerdict)
        if (changed != null) put("changed", changed)
        if (postPackageName != null) put("postPackageName", postPackageName)
    }

    /** Payload for callers that have yet to supply terminal metrics. */
    fun sessionFinished(kind: String, verified: Boolean, summaryFingerprint: String?): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("verified", verified)
            put("summaryFingerprint", summaryFingerprint?.let { JsonPrimitive(it) } ?: JsonNull)
        }

    /** Pass the summary fingerprint when available and verified=false for a finish_run claim. */
    fun sessionFinished(
        kind: String,
        verified: Boolean,
        summaryFingerprint: String?,
        actionsExecuted: Int,
        elapsedMs: Long,
        reasonFingerprint: String? = null,
    ): JsonObject = buildJsonObject {
        sessionFinished(kind, verified, summaryFingerprint).forEach { (key, value) -> put(key, value) }
        put("actionsExecuted", actionsExecuted)
        put("elapsedMs", elapsedMs)
        put("reasonFingerprint", reasonFingerprint?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun dirsToPrune(entries: List<Pair<String, Long>>, keep: Int = RETENTION_KEEP): List<String> =
        entries.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
            .drop(keep)
            .map { it.first }

    fun retentionPrune(
        entries: List<Triple<String, Long, Boolean>>,
        activeName: String?,
        keep: Int = RETENTION_KEEP,
    ): List<String> {
        return retentionPrune(entries, setOfNotNull(activeName), keep)
    }

    fun retentionPrune(
        entries: List<Triple<String, Long, Boolean>>,
        protectedNames: Set<String>,
        keep: Int = RETENTION_KEEP,
    ): List<String> {
        val marked = entries.filter { it.third }.map { it.first to it.second }
        return dirsToPrune(marked, keep).filterNot { it in protectedNames }
    }
}
