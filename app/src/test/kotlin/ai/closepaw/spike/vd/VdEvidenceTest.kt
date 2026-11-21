package ai.closepaw.spike.vd

import ai.closepaw.adb.ShellResult
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VdEvidenceTest {
    @get:Rule val activityFiles = TemporaryFolder()
    @Test
    fun persistedVerdictRequiresEveryP1Observation() {
        val frame = buildJsonObject { put("digest", "captured-fixture-digest") }
        val root = buildJsonObject { put("package", FIXTURE_PACKAGE) }
        val proof1 = buildJsonObject {
            put("capturedFixtureFrame", frame)
            put("accessibilityRoot", root)
        }
        val release = buildJsonObject { put("liveHelperReleaseAcknowledged", true) }
        val death = buildJsonObject { put("passed", true) }
        val deaths = JsonObject(mapOf("helperOnly" to death, "appOnly" to death))

        fun verdict(
            p1: JsonObject = proof1,
            p4: JsonObject = release,
            endpoints: JsonObject = deaths,
            p4Outcome: VdOutcome = VdOutcome.PASSED,
        ): Boolean {
            val log = VdStage.entries.fold(VdStageLog("run", endpointDeaths = endpoints)) { log, stage ->
                val evidence = when (stage) {
                    VdStage.PROOF_1 -> p1
                    VdStage.PROOF_4 -> p4
                    else -> JsonObject(emptyMap())
                }
                log.next(stage, if (stage == VdStage.PROOF_4) p4Outcome else VdOutcome.PASSED, 0, evidence)
            }
            return (decodeVdStages(log.json().toString()) as VdStageLoad.Present).log.terminalSuccess
        }

        assertTrue(verdict())
        assertFalse("missing post-launch frame", verdict(p1 = JsonObject(proof1 - "capturedFixtureFrame")))
        assertFalse("missing production root", verdict(p1 = JsonObject(proof1 - "accessibilityRoot")))
        assertFalse("missing release acknowledgement", verdict(p4 = JsonObject(emptyMap())))
        assertFalse("unconfirmed release", verdict(p4 = buildJsonObject { put("liveHelperReleaseAcknowledged", false) }))
        assertFalse("release evidence in failed stage", verdict(p4Outcome = VdOutcome.FAILED))
        for (endpoint in deaths.keys) {
            assertFalse("missing $endpoint", verdict(endpoints = JsonObject(deaths - endpoint)))
            assertFalse("failed $endpoint", verdict(endpoints = JsonObject(deaths + (endpoint to
                buildJsonObject { put("passed", false) }))))
        }
    }

    private val stack = """
        RootTask id=42 bounds=[0,0][720,1280] displayId=5 userId=0
         configuration={ winConfig={ mWindowingMode=fullscreen } }
         taskId=43: ai.closepaw.fixture/.Probe bounds=[0,0][720,1280] userId=0 visible=true topActivity=ComponentInfo{ai.closepaw.top/.Top}
        RootTask id=12 bounds=[0,0][720,1280] displayId=0 userId=0
         configuration={ winConfig={ mWindowingMode=pinned } }
         taskId=12: ai.closepaw.main/.Home userId=0 visible=false
    """.trimIndent()

    private val activities = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #5 (activities from top to bottom):
          * Task{1608 #42 type=standard I=ai.closepaw.intent/.Main U=0 visible=true mode=fullscreen sz=1}
            * Task{1901 #43 type=standard}
              * Hist #1: ActivityRecord{24 u0 ai.closepaw.top/.Top t43}
              * Hist #0: ActivityRecord{21 u0 ai.closepaw.fixture/.Probe t43}
        Display #0 (activities from top to bottom):
          * Task{2c11 #12 type=standard}
            * Hist #0: ActivityRecord{90 u0 ai.closepaw.main/.Home t12}
    """.trimIndent()

    @Test fun stagedActivitiesFilterPreservesDumpEvidence() {
        val details = "                state=RESUMED savedState=" + "x".repeat(1024)
        val full = activities.replace(
            "Display #0",
            List(70) { details }.joinToString("\n") + "\n" + """
                |  Task scope ended
                |      *hIsT   #0: ActivityRecord{a u0 ai.closepaw.detached/.Other t44}
                |  Resumed: ActivityRecord{b u0 ai.closepaw.reference/.Other t45}
                |  ResumedActivity: ActivityRecord{c u0 ai.closepaw.reference/.Other t46}
                |  topPausingActivity=ActivityRecord{d u0 ai.closepaw.reference/.Other t47}
                |  topResumedActivity=ActivityRecord{e u0 ai.closepaw.reference/.Other t48}
                |  mLastPausedActivity: ActivityRecord{f u0 ai.closepaw.reference/.Other t49}
                |Display #0
            """.trimMargin(),
        )
        val expected = checkNotNull(parseActivities(ShellResult(full, 0)))
        assertEquals(setOf(12, 43, 44, 45, 46, 47, 48, 49), expected.referencedTaskIds)
        assertTrue(full.length > 64 * 1024)
        val filtered = filterActivities(full)
        assertFalse(filtered.stdout.contains("savedState="))
        assertEquals(expected, parseActivities(filtered))
    }

    @Test fun stagedActivitiesFilterPreservesMalformedTaskAndDiagnostics() {
        val malformed = "  * Task{malformed"
        val filtered = filterActivities("$activities\n$malformed")
        assertEquals(0, filtered.exitCode)
        assertTrue(filtered.stdout.lines().contains(malformed))
        assertNull(parseActivities(filtered))

        val timedOut = filterActivities("$activities\nignored detail\r*** SERVICE 'activity' DUMP TIMEOUT")
        assertEquals(0, timedOut.exitCode)
        assertNull(parseActivities(timedOut))
    }

    @Test fun stagedActivitiesFilterPreservesDumpsysFailure() {
        val filtered = filterActivities(activities, dumpsysExit = 17)
        assertEquals(17, filtered.exitCode)
        assertEquals("", filtered.stdout)
        assertNull(parseActivities(filtered))
    }

    private fun filterActivities(full: String, dumpsysExit: Int = 0): ShellResult {
        val source = activityFiles.newFile().apply { writeText(full) }
        val command = activitiesObservationCommand(activityFiles.root.path)
        val process = ProcessBuilder(
            "sh", "-c",
            "source=\$1; dumpsys() { cat \"\$source\"; return $dumpsysExit; }; $command",
            "filter-test", source.path,
        ).start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        return ShellResult(stdout, process.waitFor())
    }

    @Test fun rootAndChildIdsAreDistinct() {
        val roots = parseStack(ShellResult(stack, 0))!!
        assertEquals(listOf(42, 12), roots.map { it.id })
        assertEquals(setOf(43), roots.first().childIds)
        assertEquals(true, roots.first().visible)
        assertTrue(roots.last().pinned!!)
        assertEquals("ai.closepaw.top", roots.first().topPackage)
    }

    @Test fun activityTaskHeadersAcceptClosingBraceAfterFinalField() {
        val dump = """
            Display #5 (activities from top to bottom):
              * Task{1608 #42 visible=true}
              * Task{1901 #43 I=ai.closepaw.intent/.Main}
              * Task{2c11 #12}
        """.trimIndent()

        val tasks = parseActivities(ShellResult(dump, 0))!!.tasks
        assertEquals(listOf(42, 43, 12), tasks.map { it.id })
        assertEquals(true, tasks[0].visible)
        assertEquals("ai.closepaw.intent", tasks[1].intentPackage)
    }

    @Test fun unknownIsDifferentFromDisplayWithNoTask() {
        assertNull(parseStack(ShellResult(stack, null)))
        assertNull(parseStack(ShellResult(stack, 0, truncated = true)))
        assertNull(parseStack(ShellResult("Permission Denial", 0)))
        assertNull(parseActivities(ShellResult("$activities\n*** SERVICE 'activity' DUMP TIMEOUT", 0)))
        assertNull(parseStack(ShellResult("RootTask id=42", 0)))
        val observed = mergeTasks(parseStack(ShellResult(stack, 0))!!, parseActivities(ShellResult(activities, 0))!!)
        assertTrue(observed.onDisplay(0)!!.isNotEmpty())
        assertNull(observed.onDisplay(-1))
        assertNull(observed.onDisplay(8))
        val empty = mergeTasks(emptyList(), parseActivities(ShellResult("Display #5 (activities from top to bottom):", 0))!!)
        assertEquals(emptyList<ObservedTask>(), empty.onDisplay(5))
    }

    @Test fun joinsPackagesAtRootAndReportsApproximations() {
        val dump = parseActivities(ShellResult(activities, 0))!!
        val observed = mergeTasks(parseStack(ShellResult(stack, 0))!!, dump)
        assertEquals(setOf(42), observed.idsForPackage(5, "ai.closepaw.fixture"))
        assertEquals(emptySet<Int>(), observed.idsForPackage(5, "ai.closepaw.orig"))
        assertEquals(setOf(42), observed.idsForPackage(5, "ai.closepaw.intent"))
        assertEquals("ai.closepaw.main", observed.onDisplay(0)!!.single().packageName)
        assertEquals("approximated", observed.comparison(0, 5).getValue("packageFilteredRootIds").grade)
        assertEquals("pending", observed.comparison(0, 5).getValue("nullOnFailure").grade)
        assertEquals("approximated", observed.comparison(0, 5).getValue("nullableVisibility").grade)
    }

    @Test fun nullOnFailureRequiresACompleteObservedFailure() {
        val observed = mergeTasks(parseStack(ShellResult(stack, 0))!!, parseActivities(ShellResult(activities, 0))!!)
        val failure = "Error: unknown command 'vd_phase0_invalid_probe'"
        for ((result, grade) in listOf(
            ShellResult(stack, 0) to "pending",
            ShellResult(failure, 0) to "pending",
            ShellResult(failure, null) to "pending",
            ShellResult(failure, 255, truncated = true) to "pending",
            ShellResult("Error: other failure", 255) to "pending",
            ShellResult(failure, 255) to "reproduced",
        )) {
            assertEquals(grade, observed.comparison(0, 5, stackFailureObserved(result)).getValue("nullOnFailure").grade)
        }
    }

    @Test fun usesDisplayZeroWindowFocusAndRejectsAmbiguousOrIncompleteDumps() {
        val dump = """
            WINDOW MANAGER DISPLAY CONTENTS
            mCurrentFocus=Window{bad u0 global-focus}
              Display: mDisplayId=5
                mCurrentFocus=Window{abc u0 ai.closepaw.vdprobe/.ProbeActivity}
              Display: mDisplayId=0 (organized)
                mCurrentFocus=Window{def u0 ai.closepaw.vdprobe/.ProbeActivity}
        """.trimIndent()
        assertEquals("Window{def u0 ai.closepaw.vdprobe/.ProbeActivity}", parseDisplayZeroFocus(ShellResult(dump, 0)))
        assertNull(parseDisplayZeroFocus(ShellResult(dump, 0, truncated = true)))
        assertNull(parseDisplayZeroFocus(ShellResult(dump.substringBefore("Display: mDisplayId=0"), 0)))
        assertNull(parseDisplayZeroFocus(ShellResult(dump.replace("Window{def u0 ai.closepaw.vdprobe/.ProbeActivity}", "null"), 0)))
        assertNull(parseDisplayZeroFocus(ShellResult(dump + "\n mCurrentFocus=Window{123 u0 other}", 0)))
    }

    @Test fun aRerunRequiresVerificationOfEveryJournaledItem() {
        val journal = VdJournal(
            "/data/local/tmp/closepaw-vd-0123456789abcdef01234567", 123, 5, setOf(41, 42), "process-token",
        )
        val log = VdStageLog("run", journal = journal)
        val allGone = recoveryItemNames(journal).associateWith { true }
        assertFalse(log.rerunAllowed)
        for (item in allGone.keys) {
            assertFalse(log.copy(recovery = VdRecoveryEvidence(allGone + (item to false)).json()).rerunAllowed)
            assertFalse(log.copy(recovery = VdRecoveryEvidence(allGone - item).json()).rerunAllowed)
        }
        val recovered = log.copy(recovery = VdRecoveryEvidence(allGone).json())
        assertTrue(recovered.rerunAllowed)
        assertFalse(recovered.terminalSuccess)
        assertEquals(VdStageLoad.Present(recovered), decodeVdStages(recovered.json().toString()))
        assertEquals(VdStageLoad.Invalid, decodeVdStages(recovered.json().toString().replace(journal.stagingDir!!, "/data/local/tmp/other")))
        val partial = VdStageLog("partial", journal = VdJournal(stagingDir = journal.stagingDir))
        assertFalse(partial.copy(recovery = VdRecoveryEvidence(mapOf("stagingDir:${journal.stagingDir}" to true)).json()).rerunAllowed)
    }

    @Test fun processInventoryFailsClosed() {
        val name = "closepaw-vd-0123456789abcdef01234567"
        assertEquals(mapOf(1 to "init", 123 to name), parseRecoveryProcesses(ShellResult(" PID  NAME\n1 init\n123 $name", 0)))
        assertNull(parseRecoveryProcesses(ShellResult("PID NAME\n1 init", 0, truncated = true)))
        assertNull(parseRecoveryProcesses(ShellResult("PID NAME\n1 init\n1 another", 0)))
        assertNull(parseRecoveryProcesses(ShellResult("process list unavailable", 0)))
    }

    @Test fun anEmptyRootHasUnknownVisibility() {
        val roots = parseStack(ShellResult(stack.substringBefore(" taskId=43:"), 0))!!
        assertNull(roots.single().visible)
    }

    @Test fun rejectsMixedOrIncompletePolls() {
        assertNull(parseStack(ShellResult(stack + "\nTask id=90", 0)))
        val dump = parseActivities(ShellResult(activities.replace("Display #5", "Display #9"), 0))!!
        val observed = mergeTasks(parseStack(ShellResult(stack, 0))!!, dump)
        assertNull(observed.onDisplay(5))
        assertEquals("missing", observed.comparison(0, 5).getValue("rootTaskId").grade)
    }

    @Test fun fixtureEvidenceDistinguishesWindowsAndInput() {
        val main = """
            TASK 10123:ai.closepaw.vdprobe id=12 userId=0 displayId=0(type=INTERNAL)
             ACTIVITY ai.closepaw.vdprobe/.ProbeActivity 1122 pid=10123 userId=0 uid=10123 displayId=0(type=INTERNAL)
              VD_PHASE0 run=ab role=main instance=cd display=0 task=12 attached=true drawn=true focused=true x=50 y=70 taps=0 keys=0 text=
        """.trimIndent()
        val vd = main.replace("role=main instance=cd display=0 task=12", "role=vd instance=ef display=5 task=42")
            .replace("id=12 userId=0 displayId=0(type=INTERNAL)", "id=42 userId=0 displayId=5(type=VIRTUAL)")
            .replace("uid=10123 displayId=0(type=INTERNAL)", "uid=10123 displayId=5(type=VIRTUAL)")
            .replace("taps=0 keys=0 text=", "taps=1 keys=9 text=VdProbe42")
        val windows = parseFixture(ShellResult("$main\n$vd", 0))!!
        assertEquals(listOf(0, 5), windows.map { it.display })
        assertEquals("VdProbe42", windows.last().text)
        assertFalse(windows.first().sameInputAs(windows.last()))
        assertNull(parseFixture(ShellResult("$main\n$main", 0)))
        assertNull(parseFixture(ShellResult(vd.replace("taps=1", "taps=no"), 0)))
        assertNull(parseFixture(ShellResult(vd.replace("display=5 task=42", "display=9 task=42"), 0)))
    }

    @Test fun aVisibilityRaceInvalidatesTheObservation() {
        val stackRoots = parseStack(ShellResult(stack, 0))!!
        val dump = parseActivities(ShellResult(activities.replace("visible=true", "visible=false"), 0))!!
        assertNull(mergeTasks(stackRoots, dump).onDisplay(5))
    }

    @Test fun stageLoadIsFailClosed() {
        assertEquals(VdStageLoad.Missing, decodeVdStages(null))
        assertEquals(VdStageLoad.Invalid, decodeVdStages(""))
        assertEquals(VdStageLoad.Invalid, decodeVdStages("""{"stages":[]}"""))
        val initial = VdStageLog("run")
        val log = initial.next(VdStage.PROOF_1, VdOutcome.FAILED, 3)
            .next(VdStage.PROOF_2, VdOutcome.SKIPPED, 0)
            .next(VdStage.PROOF_3, VdOutcome.SKIPPED, 0)
            .next(VdStage.PROOF_4, VdOutcome.SKIPPED, 0)
            .next(VdStage.CLEANUP, VdOutcome.PASSED, 1)
        assertEquals(VdStageLoad.Present(log), decodeVdStages(log.json().toString()))
        assertEquals(VdStageLoad.Invalid, decodeVdStages(log.json().toString().replace("failed", "passed")))
        assertEquals(false, log.terminalSuccess)
        assertEquals(false, initial.terminalSuccess)
    }
}
