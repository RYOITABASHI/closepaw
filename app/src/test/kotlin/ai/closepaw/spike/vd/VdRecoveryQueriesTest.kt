package ai.closepaw.spike.vd

import ai.closepaw.adb.ShellResult
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VdRecoveryQueriesTest {
    private fun query(command: String, dump: String, status: Int = 0): ShellResult {
        val input = Files.createTempFile("vd-recovery", ".txt")
        return try {
            Files.writeString(input, dump)
            val process = ProcessBuilder("bash", "-c", """
                dumpsys() { cat; return $status; }
                am() { cat; return $status; }
                $command
            """.trimIndent()).redirectInput(input.toFile()).redirectErrorStream(true).start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            ShellResult(stdout, process.waitFor())
        } finally {
            Files.delete(input)
        }
    }

    private val defaultDisplay = "Display Devices: size=1\n  Logical Displays: size=1\n  Display 0:\n"
    private val defaultActivities = "Display #0 (activities from top to bottom):\n"

    private fun verify(
        display: String = defaultDisplay,
        activities: String = defaultActivities,
        stack: String = "RootTask id=5 bounds=[0,0][1,1] displayId=0 userId=0\n",
        staging: String = "",
        baselineDisplayIds: Set<Int> = emptySet(),
        baselineDisplayDump: ShellResult? = null,
    ) = runBlocking {
        VdRecovery(connect = { error("Verification should not connect") }) { command, _ ->
            when (command) {
                recoveryDisplayQuery -> {
                    check(baselineDisplayDump == null) { "Baseline verification should reuse the display extraction" }
                    query(command, display)
                }
                recoveryActivitiesQuery -> query(command, activities)
                recoveryStackQuery -> query(command, stack)
                "ps -A -w -o PID,NAME" -> ShellResult("PID NAME\n1 init\n", 0)
                "pidof $FIXTURE_PACKAGE" -> ShellResult("", 1)
                else -> {
                    check(command.startsWith("[ -r /data/local/tmp ]"))
                    ShellResult(staging, 0)
                }
            }
        }.verify(
            VdJournal(stagingDir = stagedPath, displayId = 7, ownedTaskIds = setOf(42, 43), baselineDisplayIds = baselineDisplayIds),
            baselineDisplayDump,
        )
    }

    private val stagedPath = "/data/local/tmp/closepaw-vd-" + "a".repeat(24)

    @Test fun layoutMapBoundaryCannotHideLogicalDisplay() {
        val dump = defaultDisplay + "  DeviceStateToLayoutMap :\n  LogicalDisplay #7: unexpected format\n"
        val verdicts = verify(display = dump).items
        assertEquals(false, verdicts["displayNamespace"])
        assertEquals(false, verdicts["displayId:7"])
    }

    @Test fun layoutDisplayCannotMaskLiveBracketedLogicalDisplay() {
        val dump = "Display Devices: size=2\nLogical Displays: size=2\nDisplay 0:\n" +
            "LogicalDisplay[id=7]\nLayout: Display 5:\n"
        val verdicts = verify(display = dump, baselineDisplayIds = setOf(0, 5)).items
        assertEquals(false, verdicts["displayNamespace"])
        assertEquals(false, verdicts["displayId:7"])
    }

    @Test fun displayDumpBaselineIncludesBuiltInDisplayWithoutAStackRoot() {
        val dump = "Display Devices: size=2\nLogical Displays: size=2\nDisplay 0:\nLogicalDisplay[id=5]\n"
        val extraction = query(recoveryDisplayQuery, dump)
        val baseline = checkNotNull(parseRecoveryDisplay(extraction))
        assertEquals(setOf(0, 5), baseline.displayIds)
        assertFalse(verify(display = dump, baselineDisplayIds = setOf(0)).confirmed)
        assertTrue(verify(baselineDisplayIds = baseline.displayIds, baselineDisplayDump = extraction).confirmed)
    }

    @Test fun incompleteDisplayDumpBlocksBothDisplayVerdicts() {
        for (dump in listOf(defaultDisplay.substringAfter('\n'), defaultDisplay.replace("  Logical Displays: size=1\n", ""))) {
            val verdicts = verify(display = dump).items
            assertEquals(false, verdicts["displayNamespace"])
            assertEquals(false, verdicts["displayId:7"])
        }
    }

    @Test fun resumedActivityBoundaryCannotHidePausedTaskReference() {
        val dump = defaultActivities + """
              * Task{abc #5}
                ResumedActivity: ActivityRecord{abc u0 com.example/.Main t5}
                mLastPausedActivity:  ActivityRecord{def u0 com.example/.Main t42}
        """.trimIndent().prependIndent("  ")
        val verdicts = verify(activities = dump).items
        assertEquals(false, verdicts["taskId:42"])
        assertEquals(true, verdicts["taskId:43"])
    }

    @Test fun unexpectedStagingOutputBlocksBothAbsenceVerdicts() {
        val verdicts = verify(staging = "closepaw_inventory=unknown\n").items
        assertEquals(false, verdicts["stagingNamespace"])
        assertEquals(false, verdicts["stagingDir:$stagedPath"])
    }

    @Test fun stagingOutputUsesExactPaths() {
        val verdicts = verify(staging = "$stagedPath\n").items
        assertEquals(false, verdicts["stagingNamespace"])
        assertEquals(false, verdicts["stagingDir:$stagedPath"])
        val other = verify(staging = stagedPath.dropLast(1) + "b\n").items
        assertEquals(false, other["stagingNamespace"])
        assertEquals(true, other["stagingDir:$stagedPath"])
    }

    @Test fun variantLogicalDisplayRowBlocksNamespaceAndJournaledDisplayAbsence() {
        assertTrue(verify().confirmed)
        val verdicts = verify(display = defaultDisplay + "    LogicalDisplay 7: unexpected format\n").items
        assertEquals(false, verdicts["displayNamespace"])
        assertEquals(false, verdicts["displayId:7"])
    }

    @Test fun variantActivityDisplayHeaderStillAccountsForDisplayAndTaskIds() {
        assertTrue(verify().confirmed)
        val variant = "  Display  #7 (activities from top to bottom):\n    * Task{abc #42}\n"
        for (dump in listOf(variant + defaultActivities, defaultActivities + variant)) {
            val verdicts = verify(activities = dump).items
            assertEquals(false, verdicts["displayId:7"])
            assertEquals(false, verdicts["taskId:42"])
            assertEquals(true, verdicts["taskId:43"])
            assertEquals(true, verdicts["fixturePackage"])
        }
    }

    @Test fun unrelatedRowsAcrossIndentationChangesLeaveCandidateSetsIntact() {
        val layout = "  DeviceStateToLayoutMap :\n    Registered Layouts:\n"
        assertTrue(verify(display = defaultDisplay + layout).confirmed)
        val display = defaultDisplay + "Unknown section row\n" + layout
        val activities = defaultActivities + "  * Task{abc #5}\n    Unexpected task detail\n"
        assertTrue(verify(display = display, activities = activities).confirmed)
        assertEquals(false, verify(display = display + "Unknown detail displayId=7\n").items["displayId:7"])
        assertEquals(false, verify(activities = activities + "    Unexpected task detail t42\n").items["taskId:42"])
    }

    @Test fun namespaceAllowsOnlyDefaultAndRecordedBaselineDisplays() {
        val dump = defaultDisplay + "LogicalDisplay #7: pre-existing display\n"
        val journal = VdJournal(baselineDisplayIds = setOf(0, 7))
        val log = VdStageLog("test", journal = journal)
        assertEquals(VdStageLoad.Present(log), decodeVdStages(log.json().toString()))
        val verdicts = verify(display = dump, baselineDisplayIds = journal.baselineDisplayIds).items
        assertEquals(true, verdicts["displayNamespace"])
        assertEquals(false, verdicts["displayId:7"])
        assertEquals(false, verify(display = dump + "closepaw_phase0", baselineDisplayIds = journal.baselineDisplayIds)
            .items["displayNamespace"])
    }

    @Test fun parentlessActivityHistoryStillAccountsForItsTaskId() {
        val activities = defaultActivities + "  *  Hist #0: ActivityRecord{abc u0 com.example/.Main t42}\n"
        val verdicts = verify(activities = activities).items
        assertEquals(false, verdicts["taskId:42"])
        assertEquals(true, verdicts["taskId:43"])
    }

    @Test fun displayExtractionKeepsLateNamespaceHitsAndCandidateIds() {
        val large = defaultDisplay + "    mBaseDisplayInfo=DisplayInfo{built-in screen}\n".repeat(4000)
        val clean = query(recoveryDisplayQuery, large)
        assertTrue(clean.stdout.length < 128)
        assertEquals(setOf(0), parseRecoveryDisplay(clean)?.displayIds)
        assertEquals(false, parseRecoveryDisplay(clean)?.namespacePresent)

        val late = query(recoveryDisplayQuery, large + "late closepaw_phase0 Logical Display Id #7 displayId=8\n")
        assertEquals(setOf(0, 7, 8), parseRecoveryDisplay(late)?.displayIds)
        assertEquals(true, parseRecoveryDisplay(late)?.namespacePresent)
        assertNull(parseRecoveryDisplay(query(recoveryDisplayQuery, large, status = 7)))
        assertNull(parseRecoveryDisplay(query(recoveryDisplayQuery, large + "  *** SERVICE DUMP TIMEOUT\n")))
    }

    @Test fun displayExtractionRequiresDefaultDisplayAndIgnoresDeclaredCount() {
        val dump = "Display Devices:\nLogical Displays: size=2\nDisplay 0:\nLogicalDisplay{#5}\n"
        assertEquals(setOf(0, 5), parseRecoveryDisplay(query(recoveryDisplayQuery, dump))?.displayIds)
        assertEquals(setOf(0, 5), parseRecoveryDisplay(query(recoveryDisplayQuery, dump.replace("size=2", "size=3")))?.displayIds)
        assertNull(parseRecoveryDisplay(query(recoveryDisplayQuery, dump.replace("Display 0:", "Display 1:"))))
    }

    @Test fun candidateOutputMustBeCompleteAndFullyParseable() {
        val clean = ShellResult("display=0\ncomplete\n", 0)
        assertNotNull(parseRecoveryCandidates(clean))
        assertNull(parseRecoveryCandidates(clean.copy(truncated = true)))
        assertNull(parseRecoveryCandidates(clean.copy(exitCode = null)))
        for (output in listOf(
            "", "display=0", "complete", "display=0\nunknown\ncomplete",
            "display=0\ntask=99999999999999999999\ncomplete", "display=0\ncomplete\nextra",
        )) {
            assertNull(output, parseRecoveryCandidates(clean.copy(stdout = output)))
        }
    }

    @Test fun activityExtractionFindsFixtureMentionsWithoutTaskAttribution() {
        for (row in listOf(
            "* Hist #0: ActivityRecord{abc u0 $FIXTURE_COMPONENT t5}",
            "*  Hist #0: ActivityRecord{abc u0 $FIXTURE_COMPONENT t5}",
            "* hist #0: ActivityRecord{abc u0 $FIXTURE_COMPONENT t5}",
            "*Hist  #0: ActivityRecord{abc u0 $FIXTURE_COMPONENT t5}",
            "*  hist#0: unexpected fixture row $FIXTURE_PACKAGE",
            "* Task{abc #5}\n    * Hist #0: ActivityRecord{abc u0 $FIXTURE_COMPONENT t7}",
            "arbitrary detail ${FIXTURE_PACKAGE.uppercase()}",
        )) {
            val dump = defaultActivities + row + "\n"
            assertEquals(row, true, parseRecoveryCandidates(query(recoveryActivitiesQuery, dump))?.fixturePresent)
            assertEquals(row, false, verify(activities = dump).items["fixturePackage"])
        }
    }

    @Test fun activityExtractionCompactsLargeDumpsWithoutLosingUnattributedReferences() {
        val dump = defaultActivities + "  * Task{abc #5}\n" +
            "      isSleeping=false\n".repeat(4000) +
            "    unfamiliar row ActivityRecord{abc u0 com.example/.Main t42}\n" +
            "  * Task {abc # 43}\n"
        val result = query(recoveryActivitiesQuery, dump)
        assertTrue(result.stdout.length < 128)
        assertEquals(setOf(5, 42, 43), parseRecoveryCandidates(result)?.taskIds)
        assertNull(parseRecoveryCandidates(result.copy(truncated = true)))
        assertNull(parseRecoveryCandidates(query(recoveryActivitiesQuery, dump + "Permission Denial: test\n")))
        assertNull(parseRecoveryCandidates(query(recoveryActivitiesQuery, dump, status = 9)))
    }

    @Test fun stackExtractionKeepsCandidatesOnConfigurationAndVariantRows() {
        val dump = "RootTask id=12 bounds=[0,0][1,1] displayId=0 userId=0\n configuration=" +
            "large configuration ".repeat(4000) + "$FIXTURE_COMPONENT\n" +
            " TaskRecord{abc #42} Display[7]\n"
        val result = query(recoveryStackQuery, dump)
        assertTrue(result.stdout.length < 128)
        assertEquals(setOf(12, 42), parseRecoveryCandidates(result)?.taskIds)
        val verdicts = verify(stack = dump).items
        assertEquals(false, verdicts["fixturePackage"])
        assertEquals(false, verdicts["taskId:42"])
        assertEquals(true, verdicts["taskId:43"])
        assertEquals(false, verdicts["displayId:7"])
        assertNull(parseRecoveryCandidates(result.copy(truncated = true)))
        assertNull(parseRecoveryCandidates(query(recoveryStackQuery, dump, status = 4)))
    }

    @Test fun pidofAbsenceRequiresTheExpectedStatusAndEmptyOutput() {
        assertEquals(true, parseFixturePidAbsence(ShellResult("\n", 1)))
        assertEquals(false, parseFixturePidAbsence(ShellResult("123 456\n", 0)))
        assertNull(parseFixturePidAbsence(ShellResult("", 0)))
        assertNull(parseFixturePidAbsence(ShellResult("permission denied", 1)))
        assertNull(parseFixturePidAbsence(ShellResult("", 1, truncated = true)))
        assertNull(parseFixturePidAbsence(ShellResult("", null)))
    }

    @Test fun diagnosticsRecordTypedValuesWithoutChangingTheVerdict() {
        val journal = VdJournal()
        val verdicts = recoveryItemNames(journal).associateWith { true }
        val diagnostics = shellEvidence("fixturePid", ShellResult("é", 1, truncated = true), parsed = false)
        val evidence = VdRecoveryEvidence(verdicts, diagnostics)
        val items = evidence.json().getValue("items").jsonObject
        assertEquals(JsonPrimitive(1), items["diag:fixturePidExitCode"])
        assertEquals(JsonPrimitive(2), items["diag:fixturePidStdoutBytes"])
        assertEquals(JsonPrimitive(true), items["diag:fixturePidTruncated"])
        assertEquals(JsonPrimitive(true), items["diag:fixturePidCompleteOutputNull"])
        assertEquals(JsonPrimitive(false), items["diag:fixturePidParsed"])
        assertEquals(JsonNull, shellEvidence("missing", null, parsed = false)["diag:missingExitCode"])
        assertTrue(evidence.confirmed)
        assertTrue(recoveryConfirmed(evidence.json(), journal))
        assertFalse(recoveryConfirmed(VdRecoveryEvidence(verdicts + ("fixturePackage" to false), diagnostics).json(), journal))
    }
}
