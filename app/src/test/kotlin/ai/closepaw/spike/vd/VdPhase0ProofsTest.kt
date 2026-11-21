package ai.closepaw.spike.vd

import ai.closepaw.adb.AdbShellChannel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VdPhase0ProofsTest {
    @Test
    fun openingErrorIsPersistedAndRunCompletesCleanup() = runBlocking {
        val prefix = "TLS initializer failed. ".repeat(10).take(200)
        val saved = runWithOpeningAction {
            throw LinkageError(prefix + "This suffix should be truncated")
        }

        assertFailedRun(saved, "throwable:java.lang.LinkageError: $prefix")
    }

    @Test
    fun errorWithThrowingMessageIsPersistedAndRunCompletesCleanup() = runBlocking {
        val saved = runWithOpeningAction { throw ThrowingMessageError() }

        assertFailedRun(saved, "throwable:${ThrowingMessageError::class.java.name}: <message unavailable>")
    }

    @Test
    fun nestedTimeoutStillRecordsStageTimeout() = runBlocking {
        val saved = runWithOpeningAction {
            withTimeout(1L) { awaitCancellation() }
        }

        assertFailedRun(saved, "stage_timed_out")
    }

    private class ThrowingMessageError : Error() {
        override val message: String
            get() = throw IllegalStateException("Message getter failed")
    }

    private suspend fun runWithOpeningAction(connect: suspend () -> Boolean): List<VdStageLog> {
        val shell = mockk<AdbShellChannel>()
        coEvery { shell.connectAuto() } coAnswers { connect() }
        val saved = mutableListOf<VdStageLog>()

        VdPhase0Proofs(
            context = mockk(relaxed = true),
            shell = shell,
            log = VdStageLog("run", journal = VdJournal()),
            save = { saved.add(it) },
            processToken = "process",
            appDeathCheck = false,
        ).run()

        return saved
    }

    private fun assertFailedRun(saved: List<VdStageLog>, reason: String) {
        assertEquals(VdStage.entries, saved.map { it.stages.last().stage })
        val persisted = (decodeVdStages(saved.last().json().toString()) as VdStageLoad.Present).log
        val stages = persisted.stages
        assertEquals(
            listOf(VdOutcome.FAILED, VdOutcome.SKIPPED, VdOutcome.SKIPPED, VdOutcome.SKIPPED, VdOutcome.PASSED),
            stages.map { it.outcome },
        )
        assertEquals(reason, stages.first().evidence.getValue("reason").jsonPrimitive.contentOrNull)
        assertEquals(true, stages.last().evidence.getValue("fixtureGone").jsonPrimitive.boolean)
    }
}
