package ai.closepaw.spike.vd

import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.adb.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** The probe package and the Lane A launch namespace are reserved for this spike. */
internal class VdRecovery(private val shell: AdbShellChannel) {
    suspend fun recover(log: VdStageLog, processToken: String, save: (VdStageLog) -> Unit) {
        var journal = log.journal ?: VdJournal()
        var current = log.copy(journal = journal)
        if (withTimeoutOrNull(15_000) { shell.connectAuto() } != true ||
            exec("id -u")?.completeOutput()?.trim() != "2000"
        ) {
            val unverified = VdRecoveryEvidence(recoveryItemNames(journal).associateWith { false })
            save(current.copy(recovery = JsonObject(unverified.json() +
                ("reason" to JsonPrimitive("shell_identity_unconfirmed")))))
            return
        }

        val armedBy = journal.appDeathArmedBy
        if (armedBy != null && armedBy != processToken && current.endpointDeaths?.containsKey("appOnly") != true) {
            val before = awaitVerification(journal) { items ->
                items["helperPid:${journal.helperPid}"] == true && items["displayId:${journal.displayId}"] == true
            }
            val helperGone = before.items["helperPid:${journal.helperPid}"] == true
            val displayGone = before.items["displayId:${journal.displayId}"] == true
            val observation = buildJsonObject {
                put("runId", log.runId)
                put("stagingDir", journal.stagingDir)
                put("helperGone", helperGone)
                put("displayGone", displayGone)
                put("passed", helperGone && displayGone)
                put("observedBeforeRecoveryActions", true)
            }
            current = current.copy(endpointDeaths = JsonObject(current.endpointDeaths.orEmpty() + ("appOnly" to observation)))
            save(current)
        }

        val processes = processes()
        val ownedHelpers = processes.orEmpty().filterValues(helperName::matches)
        val paths = stagingDirs().orEmpty()
        val path = journal.stagingDir ?: paths.singleOrNull()
        val pid = journal.helperPid ?: path?.let {
            val name = it.substringAfterLast('/')
            ownedHelpers.entries.singleOrNull { entry -> entry.value == name }?.key
                ?: exec("cat $it/pid")?.completeOutput()?.trim()?.toIntOrNull()?.takeIf { id -> id > 0 }
        }
        val fixture = exec("dumpsys activity $FIXTURE_COMPONENT")?.let(::parseFixture).orEmpty()
            .filter { it.runId == log.runId }
        journal = journal.copy(
            stagingDir = path,
            helperPid = pid,
            displayId = journal.displayId ?: fixture.singleOrNull { it.role == "vd" && it.display > 0 }?.display,
            ownedTaskIds = journal.ownedTaskIds + fixture.map { it.task },
        )
        current = current.copy(journal = journal)
        save(current)

        exec("am force-stop --user current $FIXTURE_PACKAGE")
        if (pid != null && path != null && pid !in ownedHelpers) stopHelper(pid, path.substringAfterLast('/'))
        for ((helperPid, name) in ownedHelpers) stopHelper(helperPid, name)
        for (directory in paths) {
            val name = directory.substringAfterLast('/')
            val helperGone = withTimeoutOrNull(4_000) {
                while (processes()?.values?.none { it == name } != true) delay(100)
                true
            }
            if (helperGone == true) exec("rm -rf -- $directory")
        }
        val verification = awaitVerification(journal) { it.values.all { gone -> gone } }
        save(current.copy(recovery = verification.json()))
    }

    suspend fun stopHelper(pid: Int, name: String): Boolean {
        require(pid > 0 && helperName.matches(name))
        // Verify the per-launch name again at the point of signaling.
        val result = exec(
            "tr '\\000' '\\n' < /proc/$pid/cmdline | grep -Fqx -e '$name' -e '--nice-name=$name' && kill -KILL $pid"
        )
        return result?.completeOutput() != null
    }

    suspend fun awaitVerification(
        journal: VdJournal,
        done: (Map<String, Boolean>) -> Boolean,
    ): VdRecoveryEvidence {
        var last = VdRecoveryEvidence(recoveryItemNames(journal).associateWith { false })
        withTimeoutOrNull(15_000) {
            do {
                last = verify(journal)
                if (done(last.items)) break
                delay(250)
            } while (true)
        }
        return last
    }

    suspend fun verify(journal: VdJournal): VdRecoveryEvidence {
        val processes = processes()
        val paths = stagingDirs()
        val displayDump = exec("dumpsys display")
        val logicalDisplays = displayDump?.let(::parseLogicalDisplayIds)
        val displayNamespaceGone = logicalDisplays != null &&
            displayDump?.completeOutput()?.let { "Display Devices:" in it && "closepaw_phase0" !in it } == true
        val activities = exec("dumpsys activity activities")?.let(::parseActivities)?.takeIf { 0 in it.displays }
        val stack = exec("am stack list")?.let(::parseStack)?.takeIf { roots -> roots.any { it.display == 0 } }
        val fixturePid = exec("pidof $FIXTURE_PACKAGE")
        return VdRecoveryEvidence(buildMap {
            put("helperNamespace", processes != null && processes.values.none(helperName::matches))
            put("displayNamespace", displayNamespaceGone)
            put("stagingNamespace", paths != null && paths.isEmpty())
            put("fixturePackage", stack != null && stack.none {
                FIXTURE_PACKAGE in it.childPackages || it.topPackage == FIXTURE_PACKAGE
            } && activities != null && activities.tasks.none {
                it.intentPackage == FIXTURE_PACKAGE || it.activities.any { component -> component.startsWith("$FIXTURE_PACKAGE/") }
            } && fixturePid?.exitCode == 1 && !fixturePid.truncated && fixturePid.stdout.isBlank())
            journal.helperPid?.let { put("helperPid:$it", processes != null && it !in processes) }
            journal.displayId?.let { put("displayId:$it", logicalDisplays != null && it !in logicalDisplays && activities != null && it !in activities.displays) }
            journal.stagingDir?.let { put("stagingDir:$it", paths != null && it !in paths) }
            for (id in journal.ownedTaskIds) {
                put("taskId:$id", activities != null && stack != null &&
                    activities.tasks.none { it.id == id } && stack.none { it.id == id || id in it.childIds })
            }
        })
    }

    private suspend fun processes() = exec("ps -A -w -o PID,NAME")?.let(::parseRecoveryProcesses)

    private suspend fun stagingDirs(): Set<String>? {
        val output = exec(
            """[ -r /data/local/tmp ] && [ -x /data/local/tmp ] || exit 1
                |for path in /data/local/tmp/closepaw-vd-*; do
                |  if [ -e "${'$'}path" ] || [ -L "${'$'}path" ]; then printf '%s\n' "${'$'}path"; fi
                |done
            """.trimMargin()
        )?.completeOutput() ?: return null
        return output.lineSequence().filter(String::isNotBlank).filter(stagingPath::matches).toSet()
    }

    private suspend fun exec(command: String): ShellResult? = try {
        withTimeoutOrNull(7_000) { shell.exec(command, timeoutMs = 6_000) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
