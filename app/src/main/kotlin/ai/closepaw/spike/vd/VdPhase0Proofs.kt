package ai.closepaw.spike.vd

import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.adb.ShellResult
import ai.closepaw.app.AgentService
import ai.closepaw.platform.virtualdisplay.VirtualDisplayWindowAccessor
import java.io.IOException
import java.security.MessageDigest
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal class VdPhase0Proofs(
    context: Context,
    private val shell: AdbShellChannel,
    private var log: VdStageLog,
    private val save: (VdStageLog) -> Unit,
    private val processToken: String,
    private val appDeathCheck: Boolean,
) {
    private val session = VdHelperSession(context, shell)
    private val recovery = VdRecovery(shell)
    private var openAttempted = false
    private var fixtureLaunchAttempted = false
    private lateinit var vdWindow: FixtureWindow
    private lateinit var mainWindow: FixtureWindow
    private lateinit var evidence: JsonObjectBuilder
    private val commands = linkedMapOf<String, JsonObject>()

    suspend fun run() {
        try {
            stage(VdStage.PROOF_1, ::proof1)
            stage(VdStage.PROOF_2) {
                if (appDeathCheck) {
                    delay(45_000)
                    error("app_death_check_expired")
                }
                proof2()
            }
            stage(VdStage.PROOF_3, ::proof3)
            stage(VdStage.PROOF_4, ::proof4)
        } finally {
            withContext(NonCancellable) {
                // A persistence error can interrupt the sequence before the next proof is attempted.
                for (pending in VdStage.entries.drop(log.stages.size).takeWhile { it != VdStage.CLEANUP }) {
                    val outcome = if (log.stages.any { it.outcome == VdOutcome.FAILED }) {
                        VdOutcome.SKIPPED
                    } else {
                        VdOutcome.FAILED
                    }
                    log = log.next(pending, outcome, 0, buildJsonObject { put("reason", "sequence_interrupted") })
                }
                stage(VdStage.CLEANUP, ::cleanup)
            }
        }
    }

    private suspend fun stage(stage: VdStage, action: suspend () -> Unit) {
        var outcome = VdOutcome.PASSED
        val start = SystemClock.elapsedRealtime()
        val record = buildJsonObject {
            evidence = this
            commands.clear()
            if (stage != VdStage.CLEANUP && log.stages.any { it.outcome == VdOutcome.FAILED }) {
                outcome = VdOutcome.SKIPPED
                put("reason", "prior_proof_failed")
            } else {
                try {
                    val timeout = if (stage == VdStage.PROOF_1 && appDeathCheck) 150_000L else 70_000L
                    withTimeout(timeout) { action() }
                } catch (error: TimeoutCancellationException) {
                    outcome = VdOutcome.FAILED
                    put("reason", "stage_timed_out")
                } catch (error: Exception) {
                    outcome = VdOutcome.FAILED
                    put("reason", if (error is IllegalStateException) error.message else error.javaClass.simpleName)
                } finally {
                    put("commands", JsonObject(commands.toMap()))
                }
            }
        }
        var next = log.next(stage, outcome, SystemClock.elapsedRealtime() - start, record)
        if (stage == VdStage.PROOF_1 && outcome == VdOutcome.PASSED && appDeathCheck) {
            next = next.copy(journal = checkNotNull(next.journal).copy(appDeathArmedBy = processToken))
        }
        // Keep the in-memory sequence even if the file becomes unwritable. Cleanup still runs.
        log = next
        try {
            save(next)
        } catch (error: Exception) {
            if (stage != VdStage.CLEANUP && outcome == VdOutcome.PASSED) {
                log = next.copy(stages = next.stages.dropLast(1)).next(
                    stage, VdOutcome.FAILED, next.stages.last().elapsedMs,
                    JsonObject(record + ("reason" to JsonPrimitive("stage_store_failed"))),
                )
            }
            throw error
        }
    }

    private suspend fun proof1() {
        check(withTimeout(15_000) { shell.connectAuto() }) { "ADB connection unavailable" }
        check(exec("uid", "id -u").completeOutput()?.trim() == "2000") { "Shell UID was not confirmed" }
        check(exec("fixtureInstalled", "pm path --user current $FIXTURE_PACKAGE").completeOutput()
            ?.lineSequence()?.any { it.startsWith("package:") } == true) { "Install the dedicated VD probe APK" }
        val baseline = requireNotNull(parseStack(exec("baseline", STACK))) { "Stack baseline unknown" }
        check(baseline.any { it.display == 0 } && baseline.none(::mentionsFixture) && processAbsent(FIXTURE_PACKAGE)) {
            "Expected a known display 0 baseline and an inactive fixture package"
        }

        val baselineCleanup = recovery.verify(VdJournal())
        evidence.put("resourceBaseline", baselineCleanup.json())
        check(baselineCleanup.confirmed) { "Spike resource baseline was not clean" }
        if (appDeathCheck) observeHelperDeath()
        val display = openJournaled()
        evidence.put("displayId", display.displayId)
        evidence.put("helperPid", display.helperPid)
        evidence.put("flagsUsed", JsonArray(display.flagsUsed.map(::JsonPrimitive)))
        evidence.put("frameWidth", display.frameWidth)
        evidence.put("frameHeight", display.frameHeight)
        evidence.put("firstFrameBytes", display.firstFrameBytes)
        evidence.put("component", FIXTURE_COMPONENT)
        check(session.isOpen && display.displayId > 0 && display.helperPid > 0 && display.flagsUsed.containsAll(REQUIRED_FLAGS) &&
            display.frameWidth > 0 && display.frameHeight > 0 && display.firstFrameBytes > 0) {
            "Lane A session did not provide positive display/frame evidence"
        }
        launch(display.displayId, "vd")
        vdWindow = await("Drawn VD window was not confirmed") { window("vd", display.displayId) }
        evidence.put("window", vdWindow.json())
        evidence.put("expectedFixtureDigest", fixtureDigest)
        await("Captured frame did not match the fixture pattern") {
            val frame = session.captureFrame(1_500) ?: return@await null
            evidence.put("capturedFixtureFrame", buildJsonObject {
                put("width", frame.frameWidth)
                put("height", frame.frameHeight)
                put("bytes", frame.bytes)
                put("digest", frame.digest)
            })
            true.takeIf {
                frame.frameWidth == display.frameWidth && frame.frameHeight == display.frameHeight &&
                    frame.bytes > 0 && frame.digest == fixtureDigest
            }
        }
        val root = await("Production window accessor did not return the fixture root") {
            withContext(Dispatchers.Main.immediate) {
                val service = AgentService.instance ?: return@withContext null
                val node = VirtualDisplayWindowAccessor(service) { display.displayId }.getRootOnDisplay()
                    ?: return@withContext null
                try {
                    val packageName = node.packageName?.toString()
                    buildJsonObject {
                        put("displayId", display.displayId)
                        put("windowId", node.windowId)
                        put("package", packageName)
                        put("class", node.className?.toString())
                    }.takeIf { packageName == FIXTURE_PACKAGE }
                } finally {
                    node.recycle()
                }
            }
        }
        evidence.put("accessibilityRoot", root)
        check(session.isOpen &&
            parseRecoveryProcesses(exec("armedProcesses", "ps -A -w -o PID,NAME", retainOutput = false))
                ?.get(display.helperPid) == session.launchInfo?.stagingDir?.substringAfterLast('/') &&
            parseActivities(exec("armedDisplays", ACTIVITIES))?.displays?.contains(display.displayId) == true) {
            "Endpoints were not live at the app-death observation boundary"
        }
        evidence.put("appDeathCheckRequested", appDeathCheck)
    }

    private suspend fun proof2() {
        val display = vdWindow.display
        launch(0, "main")
        mainWindow = await("Drawn display 0 window was not confirmed") { window("main", 0) }
        evidence.put("display0Window", mainWindow.json())
        val snapshot = poll {
            observe()?.takeIf {
                it.onDisplay(0)?.any { task -> mainWindow.task in task.root.childIds } == true &&
                    it.onDisplay(display)?.any { task -> vdWindow.task in task.root.childIds } == true
            }
        }
        val observation = snapshot ?: VdTaskObservation(emptyMap())
        evidence.put("display0Tasks", observation.rows(0))
        evidence.put("vdTasks", observation.rows(display))
        evidence.put("display0FixtureRootIds", observation.idsForPackage(0, FIXTURE_PACKAGE)?.let(::ids) ?: JsonNull)
        evidence.put("vdFixtureRootIds", observation.idsForPackage(display, FIXTURE_PACKAGE)?.let(::ids) ?: JsonNull)
        val failedCommand = exec("inducedStackFailure", STACK_FAILURE_PROBE)
        val failureObserved = stackFailureObserved(failedCommand)
        val comparison = observation.comparison(0, display, failureObserved)
        evidence.put("shellObservationWorks", snapshot != null)
        evidence.put("typedContractParityProven", false)
        evidence.put("typedContractParityReason", "No typed Binder comparison was performed.")
        evidence.put("comparison", buildJsonObject {
            comparison.forEach { (field, row) ->
                put(field, buildJsonObject {
                    put("grade", row.grade)
                    put("reason", row.reason)
                })
            }
        })
        check(snapshot != null) { "Both displays did not reconcile" }
    }

    private suspend fun proof3() {
        evidence.put("attribution", "Postcondition only. The CLI discards removeTask's Boolean.")
        val display = vdWindow.display
        val beforeLaunch = await("Pre-launch task state unknown") { observeBoth(display) }
        launch(display, "owned")
        val owned = await("Owned fixture task did not appear") { window("owned", display) }
        evidence.put("ownedWindow", owned.json())
        val before = await("Pre-removal task state unknown") { observeBoth(display) }
        evidence.put("beforeDisplay0", before.rows(0))
        evidence.put("beforeVd", before.rows(display))
        val priorIds = (beforeLaunch.onDisplay(0).orEmpty() + beforeLaunch.onDisplay(display).orEmpty())
            .flatMap { it.root.childIds + it.root.id }.toSet()
        check(owned.task !in priorIds) { "Fixture task ID existed before launch" }
        val remove = removeFixtureRoot(before, owned, "remove")
        val after = await("Removed task still present or task state unknown") {
            // Check every observed display for migration, not only the source display.
            val snapshot = observeBoth(display)
            snapshot?.takeIf { !it.containsTask(owned.task) }
        }
        evidence.put("afterDisplay0", after.rows(0))
        evidence.put("afterVd", after.rows(display))
        evidence.put("ownedTaskGone", true)
        val unchanged = before.signature(0) == after.signature(0) &&
            before.signature(display).filterNot { it.id == owned.task } == after.signature(display)
        evidence.put("unrelatedTasksUnchanged", unchanged)
        check(remove.completeOutput() != null) { "Removal command returned no complete success result" }
        check(unchanged) { "Unrelated task identity, membership, or order changed" }
    }

    private suspend fun proof4() {
        val display = vdWindow.display
        val vdBefore = await("VD fixture was not foreground") { focused("vd", display) }
        val mainBefore = await("Display 0 fixture was not foreground") { focused("main", 0) }
        check(vdBefore.instance == vdWindow.instance && mainBefore.instance == mainWindow.instance) {
            "Fixture instances changed"
        }
        check(vdBefore.x >= 0 && vdBefore.y >= 0 && vdBefore.text.isEmpty()) { "VD input baseline invalid" }
        evidence.put("vdBefore", vdBefore.json())
        evidence.put("mainBefore", mainBefore.json())
        val tap = "input -d $display tap ${vdBefore.x} ${vdBefore.y}"
        val text = "input -d $display text VdProbe42"
        val tapResult = exec("liveTap", tap)
        val textResult = exec("liveText", text)
        val liveIsolation = poll {
            val windows = windows().orEmpty()
            val vdAfter = windows.singleOrNull { it.instance == vdBefore.instance && it.display == display }
            val mainAfter = windows.singleOrNull { it.instance == mainBefore.instance && it.display == 0 }
            evidence.put("vdAfter", vdAfter?.json() ?: JsonNull)
            evidence.put("mainAfter", mainAfter?.json() ?: JsonNull)
            if (mainAfter != null && !mainAfter.sameInputAs(mainBefore)) return@poll false
            true.takeIf {
                vdAfter != null && vdAfter.focused && vdAfter.taps == vdBefore.taps + 1 &&
                    vdAfter.keys > vdBefore.keys && vdAfter.text == "VdProbe42" && mainAfter?.focused == true
            }
        } == true
        evidence.put("liveInputIsolated", liveIsolation)
        check(tapResult.completeOutput() != null && textResult.completeOutput() != null && liveIsolation) {
            "Live input failed, leaked, or delivery was unconfirmed"
        }
        check(focused("main", 0)?.sameInputAs(mainBefore) == true) { "Display 0 foreground changed after live input" }

        // Public displays can reparent their tasks on release. Keep the display-0 control
        // foreground by removing only the positively identified VD fixture first.
        val beforeRelease = await("Pre-release task state unknown") { observeBoth(display) }
        val remove = removeFixtureRoot(beforeRelease, vdBefore, "removeBeforeRelease")
        await("VD fixture task removal before release was not confirmed") {
            observeBoth(display)?.takeIf { !it.containsTask(vdBefore.task) }
        }
        evidence.put("vdTaskGoneBeforeRelease", true)
        check(remove.completeOutput() != null) { "VD fixture removal command was unconfirmed" }

        check(session.isOpen) { "Helper was unavailable for live release" }
        closeAndVerify()
        evidence.put("liveHelperReleaseAcknowledged", true)
        val staleBefore = await("Display 0 fixture was not foreground after release") { focused("main", 0) }
        evidence.put("staleMainBefore", staleBefore.json())
        check(staleBefore.sameInputAs(mainBefore)) { "Display 0 fixture changed during release" }
        check(displayAbsent(display)) { "Released display ID was reported present before stale input" }
        evidence.put("staleInputLimit", "The shell records display-0 window focus immediately before and after each dispatch and the fixture input counters. A transient window can gain and lose focus between samples, and shell completion does not prove dispatch rejection or absence of delivery to another window.")
        val tapStable = dispatchStale("staleTap", tap)
        val textStable = dispatchStale("staleText", text)
        delay(400)
        val staleAfter = windows()?.singleOrNull { it.instance == staleBefore.instance && it.display == 0 }
        evidence.put("staleMainAfter", staleAfter?.json() ?: JsonNull)
        val stillAbsent = displayAbsent(display)
        evidence.put("displayAbsentAfterStaleInput", stillAbsent)
        val harmless = stillAbsent && staleAfter?.sameInputAs(staleBefore) == true &&
            focused("main", 0)?.sameInputAs(staleBefore) == true &&
            tapStable && textStable
        evidence.put("staleInputObservationsPassed", harmless)
        check(harmless) { "Stale input leaked, the display reappeared, or command completion was unknown" }
    }

    private suspend fun dispatchStale(key: String, command: String): Boolean {
        val before = parseDisplayZeroFocus(exec("${key}FocusBefore", WINDOW_DISPLAYS, retainOutput = false))
        val result = exec(key, command)
        val after = parseDisplayZeroFocus(exec("${key}FocusAfter", WINDOW_DISPLAYS, retainOutput = false))
        val unchanged = before != null && before == after
        val fixtureFocused = before?.contains(" $FIXTURE_PACKAGE/") == true
        evidence.put("${key}Focus", buildJsonObject {
            put("before", before)
            put("after", after)
            put("unchanged", unchanged)
            put("fixtureFocused", fixtureFocused)
        })
        return unchanged && fixtureFocused && !result.truncated && result.exitCode != null
    }

    private suspend fun observeHelperDeath() {
        val display = openJournaled().displayId
        val journal = checkNotNull(log.journal)
        val pid = checkNotNull(journal.helperPid)
        val name = checkNotNull(journal.stagingDir).substringAfterLast('/')
        check(session.isOpen && recovery.stopHelper(pid, name)) { "Helper-only kill was not confirmed" }

        val observation = await("Helper-only death did not release the display and invalidate the Binder") {
            val gone = pidAbsent(pid)
            val displayGone = displayAbsent(display)
            val binderDead = !session.isOpen
            val passed = gone && displayGone && binderDead
            val sample = buildJsonObject {
                put("runId", log.runId)
                put("stagingDir", journal.stagingDir)
                put("helperGone", gone)
                put("displayGone", displayGone)
                put("binderDead", binderDead)
                put("appStillRunning", true)
                put("passed", passed)
            }
            evidence.put("helperOnlyEndpointDeath", sample)
            sample.takeIf { passed }
        }

        // This sacrificial session has already proved Binder death. Close disposes
        // local resources, and Lane A reports the expected absence of its release ack.
        try {
            withTimeout(15_000) { session.close() }
        } catch (error: IOException) {
            if (error.message != "VD helper stopped without a confirmed release" ||
                error.cause?.message != "VD helper exited before explicit release" ||
                error.suppressed.isNotEmpty()
            ) throw error
            evidence.put("closeAfterHelperDeath", error.message)
        }
        val verified = recovery.awaitVerification(journal) { it.values.all { gone -> gone } }
        evidence.put("helperDeathJournalVerification", verified.json())
        check(verified.confirmed) { "Helper-death session cleanup was unconfirmed" }
        persist(log.copy(
            journal = VdJournal(),
            endpointDeaths = buildJsonObject { put("helperOnly", observation) },
        ))
    }

    private suspend fun openJournaled(): VdOpenResult = withTimeout(45_000) {
        openAttempted = true
        coroutineScope {
            val opening = async { session.open() }
            try {
                while (!opening.isCompleted) {
                    syncLaunchJournal()
                    delay(25)
                }
                opening.await()
            } finally {
                syncLaunchJournal()
            }
        }
    }

    private fun syncLaunchJournal() {
        val info = session.launchInfo ?: return
        val journal = checkNotNull(log.journal)
        val updated = journal.copy(stagingDir = info.stagingDir, helperPid = info.helperPid, displayId = info.displayId)
        if (updated != journal) persist(log.copy(journal = updated))
    }

    private fun persist(updated: VdStageLog) {
        log = updated
        save(updated)
    }

    private fun disarmAppDeath() {
        val journal = log.journal ?: return
        if (journal.appDeathArmedBy != null) persist(log.copy(journal = journal.copy(appDeathArmedBy = null)))
    }

    private suspend fun removeFixtureRoot(
        snapshot: VdTaskObservation,
        owned: FixtureWindow,
        commandKey: String,
    ): ShellResult {
        check(owned.display > 0) { "Fixture root removal requires a secondary display" }
        val task = snapshot.onDisplay(owned.display)?.singleOrNull { it.root.id == owned.task }
        check(task?.root?.childIds == setOf(owned.task) && task.packages == setOf(FIXTURE_PACKAGE) &&
            task.activities.isNotEmpty() && task.activities.all { it.startsWith("$FIXTURE_PACKAGE/") }) {
            "Standalone fixture root ownership could not be confirmed"
        }
        check(window(owned.role, owned.display)?.let { it.instance == owned.instance && it.task == owned.task } == true) {
            "Owned task identity changed"
        }
        return exec(commandKey, "am stack remove ${owned.task}")
    }

    private suspend fun cleanup() {
        try {
            disarmAppDeath()
        } finally {
            cleanupResources()
        }
    }

    private suspend fun cleanupResources() {
        var fixtureGone = !fixtureLaunchAttempted
        try {
            if (fixtureLaunchAttempted) {
                check(exec("stopFixture", "am force-stop --user current $FIXTURE_PACKAGE").completeOutput() != null) {
                    "Fixture force-stop failed"
                }
                await("Fixture task or process remains") {
                    val roots = parseStack(exec("cleanupStack", STACK))
                    true.takeIf { roots != null && roots.none(::mentionsFixture) &&
                        processAbsent(FIXTURE_PACKAGE) }
                }
                fixtureGone = true
            }
        } finally {
            evidence.put("fixtureGone", fixtureGone)
            // This also covers an open that threw before it yielded a PID or display ID.
            if (openAttempted) withContext(NonCancellable) {
                try {
                    closeAndVerify()
                } finally {
                    val verified = recovery.awaitVerification(checkNotNull(log.journal)) { it.values.all { gone -> gone } }
                    evidence.put("journalVerification", verified.json())
                    check(verified.confirmed) { "Journaled resource cleanup was unconfirmed" }
                }
            }
        }
    }

    private suspend fun closeAndVerify() {
        withTimeout(15_000) { session.close() }
        evidence.put("sessionClosed", !session.isOpen)
        check(!session.isOpen) { "Session still reports open" }
        val info = session.launchInfo ?: return
        await("Display or helper PID still present, or cleanup observation unknown") {
            val displayGone = info.displayId?.let { displayAbsent(it) } ?: true
            val helperGone = info.helperPid?.let { pidAbsent(it) } ?: true
            evidence.put("displayGone", displayGone)
            evidence.put("helperGone", helperGone)
            true.takeIf { displayGone && helperGone }
        }
    }

    private suspend fun displayAbsent(id: Int): Boolean {
        val dump = parseActivities(exec("releaseDisplays", ACTIVITIES)) ?: return false
        val logical = parseLogicalDisplayIds(exec("logicalDisplays", "dumpsys display", retainOutput = false))
        return logical != null && id !in logical && 0 in dump.displays && id !in dump.displays
    }

    private suspend fun pidAbsent(pid: Int): Boolean {
        val lines = exec("helperPs", "ps -A -o PID", retainOutput = false).completeOutput()
            ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList() ?: return false
        return lines.firstOrNull() == "PID" && lines.size > 1 &&
            lines.drop(1).all { it.toIntOrNull()?.let { id -> id != pid } == true }
    }

    private suspend fun processAbsent(name: String): Boolean {
        val result = exec("fixturePid", "pidof $name")
        return !result.truncated && result.exitCode == 1 && result.stdout.isBlank()
    }

    private suspend fun launch(display: Int, role: String) {
        require(display >= 0)
        fixtureLaunchAttempted = true
        val result = exec("launch_$role", "am start -W --user current --display $display " +
            "--activity-new-task --activity-multiple-task -n $FIXTURE_COMPONENT --es vdRun ${log.runId} --es vdRole $role")
        val output = result.completeOutput()
        check(output != null && output.lineSequence().any { it.trim() == "Status: ok" } &&
            output.lineSequence().none { it.trimStart().startsWith("Warning:") }) {
            "Fixture launch failed or reused a task"
        }
    }

    private suspend fun windows(): List<FixtureWindow>? {
        val windows = parseFixture(exec("fixtureDump", "dumpsys activity $FIXTURE_COMPONENT", retainOutput = false))
            ?: return null
        val journal = checkNotNull(log.journal)
        val ids = windows.filter { it.runId == log.runId }.map { it.task }
        val updated = journal.copy(ownedTaskIds = journal.ownedTaskIds + ids)
        if (updated != journal) persist(log.copy(journal = updated))
        return windows
    }

    private suspend fun window(role: String, display: Int): FixtureWindow? =
        windows()?.singleOrNull {
            it.runId == log.runId && it.role == role && it.display == display && it.drawn && it.attached
        }

    private suspend fun focused(role: String, display: Int): FixtureWindow? {
        val window = window(role, display)?.takeIf { it.focused } ?: return null
        val front = parseStack(exec("foregroundStack", STACK))?.firstOrNull { it.display == display }
        return window.takeIf { front?.visible == true && window.task in front.childIds }
    }

    private suspend fun observe(): VdTaskObservation? {
        val stack = parseStack(exec("stack", STACK)) ?: return null
        val dump = parseActivities(exec("activities", ACTIVITIES)) ?: return null
        return mergeTasks(stack, dump)
    }

    private suspend fun observeBoth(display: Int): VdTaskObservation? =
        observe()?.takeIf { it.onDisplay(0) != null && it.onDisplay(display) != null }

    private suspend fun exec(key: String, command: String, retainOutput: Boolean = true): ShellResult {
        val start = SystemClock.elapsedRealtime()
        var result: ShellResult? = null
        try {
            return withTimeout(7_000) { shell.exec(command, timeoutMs = 6_000) }.also { result = it }
        } finally {
            commands[key] = buildJsonObject {
                put("command", command)
                put("elapsedMs", SystemClock.elapsedRealtime() - start)
                put("exitCode", result?.exitCode?.let(::JsonPrimitive) ?: JsonNull)
                put("truncated", result?.truncated?.let(::JsonPrimitive) ?: JsonNull)
                if (retainOutput) put("outputTail", result?.stdout?.takeLast(1200))
            }
        }
    }

    private suspend fun <T : Any> await(reason: String, block: suspend () -> T?): T =
        poll(block) ?: error(reason)

    private suspend fun <T : Any> poll(block: suspend () -> T?): T? =
        withTimeoutOrNull(12_000) {
            var value = block()
            while (value == null) {
                delay(250)
                value = block()
            }
            value
        }

    private fun mentionsFixture(root: StackRoot) =
        FIXTURE_PACKAGE in root.childPackages || root.topPackage == FIXTURE_PACKAGE

    private fun ids(values: Set<Int>) = JsonArray(values.sorted().map(::JsonPrimitive))

    private fun VdTaskObservation.rows(display: Int): JsonElement = onDisplay(display)?.let { tasks ->
        JsonArray(tasks.map {
            buildJsonObject {
                put("rootTaskId", it.root.id)
                put("childIds", ids(it.root.childIds))
                put("package", it.packageName)
                put("packages", JsonArray(it.packages.sorted().map(::JsonPrimitive)))
                put("activityComponents", JsonArray(it.activities.map(::JsonPrimitive)))
                put("pinned", it.root.pinned?.let(::JsonPrimitive) ?: JsonNull)
                put("visible", it.root.visible?.let(::JsonPrimitive) ?: JsonNull)
            }
        })
    } ?: JsonNull

    private data class TaskIdentity(val id: Int, val children: Set<Int>, val activities: List<String>)

    private fun VdTaskObservation.signature(display: Int) =
        onDisplay(display).orEmpty().map { TaskIdentity(it.root.id, it.root.childIds, it.activities) }

    private companion object {
        // Lane A hashes 25 RGBA samples in the central half of the image.
        val fixtureDigest = MessageDigest.getInstance("SHA-256").apply {
            val rgba = byteArrayOf(0x19, 0xd3.toByte(), 0x87.toByte(), 0xff.toByte())
            repeat(25) { update(rgba) }
        }.digest().joinToString("") { "%02x".format(it) }
        const val WINDOW_DISPLAYS = "dumpsys window displays"
        const val STACK = "am stack list"
        const val ACTIVITIES = "dumpsys activity activities"
        val REQUIRED_FLAGS = setOf(
            "PUBLIC", "SECURE", "SUPPORTS_TOUCH", "SHOULD_SHOW_SYSTEM_DECORATIONS", "TRUSTED", "OWN_DISPLAY_GROUP",
        )
    }
}
