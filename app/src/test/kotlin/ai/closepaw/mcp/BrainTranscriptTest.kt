package ai.closepaw.mcp

import ai.closepaw.agent.cognition.context.ScreenSignature
import ai.closepaw.app.goalFingerprint
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTranscriptTest {
    @Test
    fun sessionStartedRecordsGoalFingerprintAndCorrelationFlags() {
        val goal = "private goal canary"
        val fingerprint = goalFingerprint(goal)
        val payload = BrainTranscript.sessionStarted(
            fingerprint, "test", reqIdPresent = true, noncePresent = false,
        )

        assertEquals(
            setOf(
                "goalFingerprint", "origin", "hasReqId", "hasNonce",
                "maxActions", "wallClockMs", "loopWindow", "loopSimilarity",
            ),
            payload.keys,
        )
        assertEquals(JsonPrimitive(BrainSessionLimits.MAX_ACTIONS_PER_SESSION), payload["maxActions"])
        assertEquals(JsonPrimitive(BrainSessionLimits.MAX_SESSION_WALL_CLOCK_MS), payload["wallClockMs"])
        assertEquals(JsonPrimitive(BrainSessionLimits.LOOP_WINDOW), payload["loopWindow"])
        assertEquals(JsonPrimitive(BrainSessionLimits.LOOP_SIMILARITY_THRESHOLD), payload["loopSimilarity"])
        assertEquals(JsonPrimitive(fingerprint), payload["goalFingerprint"])
        assertEquals(JsonPrimitive("test"), payload["origin"])
        assertEquals(JsonPrimitive(true), payload["hasReqId"])
        assertEquals(JsonPrimitive(false), payload["hasNonce"])
        assertFalse(payload.toString().contains(goal))
    }

    @Test
    fun observationIncludesMetadataAndOmitsAnUnknownPackage() {
        val expected = buildJsonObject {
            put("packageName", "example.app")
            put("elementCount", 3)
            put("masked", true)
            put("partiallyMasked", false)
            put("imageTaken", false)
            put("atMs", 42L)
        }

        assertEquals("observation", BrainTranscript.TYPE_OBSERVATION)
        assertEquals(expected, BrainTranscript.observation("example.app", 3, true, false, null, false, 42L))
        assertEquals(expected.keys - "packageName", BrainTranscript.observation(null, 3, true, false, null, false, 42L).keys)
        val partial = BrainTranscript.observation("example.app", 3, false, true, "screens/1.png", true, 42L)
        assertEquals(JsonPrimitive(false), partial["masked"])
        assertEquals(JsonPrimitive(true), partial["partiallyMasked"])
        assertEquals(JsonPrimitive(true), partial["imageTaken"])
        assertEquals(JsonPrimitive("screens/1.png"), partial["imageArtifactPath"])
    }

    @Test
    fun approvalIncludesPhaseAndFingerprintAndOmitsAnUnknownPackage() {
        val expected = buildJsonObject {
            put("phase", "entered")
            put("callIdFingerprint", "fingerprint")
            put("packageName", "example.app")
            put("atMs", 42L)
        }

        assertEquals("approval", BrainTranscript.TYPE_APPROVAL)
        assertEquals(expected, BrainTranscript.approval("entered", "fingerprint", "example.app", 42L))
        val resolved = BrainTranscript.approval("resolved", "fingerprint", null, 43L)
        assertEquals(expected.keys - "packageName", resolved.keys)
        assertEquals(JsonPrimitive("resolved"), resolved["phase"])
    }

    @Test
    fun typeTextActionRecordsFingerprintWithoutTypedTextOrScreenTokens() {
        val typedText = "private typed canary"
        val fingerprint = goalFingerprint(typedText)
        val screenToken = "private screen canary"
        val record = BrainActionRecord("type_text", fingerprint, ScreenSignature(setOf(screenToken)), 42)
        val payload = BrainTranscript.action(record, outcome = "ok", verified = null)

        assertTrue(fingerprint.matches(Regex("len=${typedText.length} sha=[0-9a-f]{8}")))
        assertEquals(setOf("tool", "targetKey", "outcome", "atMs"), payload.keys)
        assertEquals(JsonPrimitive("type_text"), payload["tool"])
        assertEquals(JsonPrimitive(fingerprint), payload["targetKey"])
        assertEquals(JsonPrimitive("ok"), payload["outcome"])
        assertEquals(JsonPrimitive(42L), payload["atMs"])
        assertFalse(payload.toString().contains(typedText))
        assertFalse(payload.toString().contains(screenToken))
        assertEquals(JsonPrimitive(false), BrainTranscript.action(record, "ok", false)["verified"])
    }

    @Test
    fun actionIncludesOnlySuppliedGuardAndPostActionMetadata() {
        val record = BrainActionRecord("tap", "element:2", ScreenSignature(emptySet()), 42)
        val baseline = BrainTranscript.action(record, "ok", verified = null)
        val verdict = BrainTranscript.action(record, "ok", verified = null, guardVerdict = "ok")
        val metadata = BrainTranscript.action(
            record, "ok", verified = null, guardVerdict = "loop", changed = false, postPackageName = "example.app",
        )

        assertEquals(baseline.keys + "guardVerdict", verdict.keys)
        assertEquals(JsonPrimitive("ok"), verdict["guardVerdict"])
        assertEquals(baseline.keys + setOf("guardVerdict", "changed", "postPackageName"), metadata.keys)
        assertEquals(JsonPrimitive("loop"), metadata["guardVerdict"])
        assertEquals(JsonPrimitive(false), metadata["changed"])
        assertEquals(JsonPrimitive("example.app"), metadata["postPackageName"])
    }

    @Test
    fun sessionFinishedRecordsUnverifiedClaimAndSummaryFingerprint() {
        val summary = "private summary canary"
        val fingerprint = goalFingerprint(summary)
        val payload = BrainTranscript.sessionFinished(
            "DONE", verified = false, summaryFingerprint = fingerprint, actionsExecuted = 3, elapsedMs = 42L,
        )

        assertEquals(setOf("kind", "verified", "summaryFingerprint", "reasonFingerprint", "actionsExecuted", "elapsedMs"), payload.keys)
        assertEquals(JsonPrimitive(3), payload["actionsExecuted"])
        assertEquals(JsonPrimitive(42L), payload["elapsedMs"])
        assertEquals(JsonPrimitive("DONE"), payload["kind"])
        assertEquals(JsonPrimitive(false), payload["verified"])
        assertEquals(JsonPrimitive(fingerprint), payload["summaryFingerprint"])
        assertFalse(payload.toString().contains(summary))
        val stopped = BrainTranscript.sessionFinished("STOPPED", false, null, actionsExecuted = 0, elapsedMs = 0L)
        assertEquals(JsonNull, stopped["summaryFingerprint"])
        assertEquals(JsonNull, stopped["reasonFingerprint"])
        val reason = "private termination reason"
        val failed = BrainTranscript.sessionFinished(
            "FAILED", false, null, 0, 42L, reasonFingerprint = goalFingerprint(reason),
        )
        assertEquals(JsonPrimitive(goalFingerprint(reason)), failed["reasonFingerprint"])
        assertFalse(failed.toString().contains(reason))
    }

    @Test
    fun retentionPrunesExactlyTheTenOldestDirectories() {
        val entries = (0 until 60).map { "brain-$it" to it.toLong() }

        assertEquals((9 downTo 0).map { "brain-$it" }, BrainTranscript.dirsToPrune(entries))
    }

    @Test
    fun retentionKeepsAllDirectoriesAtOrBelowTheLimit() {
        val entries = (0 until 50).map { "brain-$it" to it.toLong() }

        assertTrue(BrainTranscript.dirsToPrune(entries).isEmpty())
        assertTrue(BrainTranscript.dirsToPrune(entries.take(3)).isEmpty())
    }

    @Test
    fun retentionBreaksTimestampTiesByName() {
        val entries = listOf("brain-b" to 1L, "brain-a" to 1L)

        assertEquals(listOf("brain-b"), BrainTranscript.dirsToPrune(entries, keep = 1))
    }

    @Test
    fun retentionPrunesOnlyMarkedDirectoriesOutsideTheNewestFiftyAndProtectsTheActiveDirectory() {
        val entries = (0 until 55).map { Triple("brain-$it", it.toLong(), true) } +
            listOf(Triple("unmarked-old", -1L, false), Triple("unmarked-new", 100L, false))

        assertEquals(
            listOf("brain-4", "brain-3", "brain-1", "brain-0"),
            BrainTranscript.retentionPrune(entries, activeName = "brain-2"),
        )
    }

    @Test
    fun retentionProtectsEveryLiveDirectoryOutsideTheNewestFifty() {
        val entries = (0 until 55).map { Triple("brain-$it", it.toLong(), true) }

        assertEquals(
            listOf("brain-4", "brain-2", "brain-0"),
            BrainTranscript.retentionPrune(entries, protectedNames = setOf("brain-3", "brain-1")),
        )
    }

    @Test
    fun markedRetentionBreaksTimestampTiesByName() {
        val entries = listOf(Triple("brain-b", 1L, true), Triple("brain-a", 1L, true))

        assertEquals(listOf("brain-b"), BrainTranscript.retentionPrune(entries, activeName = null, keep = 1))
    }
}
