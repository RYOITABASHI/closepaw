package ai.closepaw.update

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class AdbUpdateStagesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun malformedStageLogFailsToDecode() {
        for (text in listOf("", "{broken", "{}", "[{}]", "[{\"stage\":\"commit-sent\",\"atMs\":1}]")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateStageLog.decode(text) }
        }
    }

    @Test fun corruptStageStoreDeclinesStartAndPreservesFile() {
        val file = temp.newFile("spike-result.json").apply { writeText("{broken") }
        val decision = startUpdateIfReady({ UpdateStageLog.read(file) }, { false }, { error("Unexpected session") }) {
            file.delete()
        }
        assertEquals(false, decision.accepted)
        assertEquals("Stage store is unreadable: spike-result.json", decision.error)
        assertEquals("{broken", file.readText())
        val status = UpdateStageLog.readStatus(null, false) {
            UpdateStageLog.statusJson(UpdateStageLog.read(file), null, false)
        }
        assertEquals(JsonPrimitive("unreadable"), status["stageStore"])
        assertEquals(JsonNull, status["stages"])
    }

    @Test fun missingStageStoreAllowsStart() {
        val file = File(temp.root, "spike-result.json")
        var launched = false
        val decision = startUpdateIfReady({ UpdateStageLog.read(file) }, { false }, { error("Unexpected session") }) {
            launched = true
        }
        assertEquals(true, decision.accepted)
        assertEquals(true, launched)
    }

    @Test fun unknownStageNameInEitherStoreDeclinesStart() {
        val shizuku = File(temp.root, "spike-result.json")
        val adb = File(temp.root, "self-update-adb.json")
        val damaged = """[{"stage":"started","atMs":1},{"stage":"damaged","atMs":2}]"""
        for (file in listOf(shizuku, adb)) {
            val contents = if (file == shizuku) damaged else """{"selfUpdateAdb":$damaged}"""
            file.writeText(contents)
            val decision = startUpdateIfReady({ UpdateStageLog.read(shizuku) }, {
                AdbUpdateStages.pendingTicket(UpdateStageLog.read(adb, "selfUpdateAdb").lastOrNull()) != null
            }, { error("Unexpected session") }) {
                error("A store with an unknown stage must block launch")
            }
            assertEquals(false, decision.accepted)
            assertEquals("Stage store is unreadable: ${file.name}", decision.error)
            assertEquals(contents, file.readText())
            check(file.delete())
        }
    }

    @Test fun unknownHistoricalStageAlsoMakesStoreUnreadable() {
        val file = temp.newFile().apply {
            writeText("""[{"stage":"damaged","atMs":1},{"stage":"result","atMs":2}]""")
        }
        assertThrows(UnreadableUpdateStageStore::class.java) { UpdateStageLog.read(file) }
    }

    @Test fun validStageStoreStillGuardsPendingSessionAndAllowsCompletedSession() {
        val commit = UpdateStageRecord("commit-sent", 100, mapOf(
            "sessionId" to JsonPrimitive(42),
            "deadlineAtMs" to JsonPrimitive(180_100L),
        ))
        val file = temp.newFile().apply { writeText(UpdateStageLog.encode(listOf(commit))) }
        var pendingId: Int? = null
        val pending = startUpdateIfReady({ UpdateStageLog.read(file) }, { false }, { pendingId = it.id }) {
            error("A pending install must block launch")
        }
        assertEquals(false, pending.accepted)
        assertEquals(42, pendingId)

        file.writeText(UpdateStageLog.encode(listOf(commit, UpdateStageRecord("result", 200))))
        var launched = false
        val completed = startUpdateIfReady({ UpdateStageLog.read(file) }, { false }, { error("Unexpected session") }) {
            launched = true
        }
        assertEquals(true, completed.accepted)
        assertEquals(true, launched)
    }

    @Test fun unreadableAdbStoreDeclinesStart() {
        val directory = temp.newFolder("self-update-adb.json")
        val decision = startUpdateIfReady({ emptyList() }, {
            AdbUpdateStages.pendingTicket(UpdateStageLog.read(directory, "selfUpdateAdb").lastOrNull()) != null
        }, { error("Unexpected session") }) {
            error("An unreadable ADB store must block launch")
        }
        assertEquals(false, decision.accepted)
        assertEquals("Stage store is unreadable: self-update-adb.json", decision.error)
        assertEquals(true, directory.isDirectory)
    }

    @Test fun validAdbTicketStillSelectsResultRecovery() {
        val stages = listOf(AdbUpdateStages.staged(100, 10, "00000000-0000-0000-0000-000000000000"))
        val adbLog = temp.newFile("self-update-adb.json").apply {
            writeText("{\"selfUpdateAdb\":${UpdateStageLog.encode(stages)}}")
        }
        var recovery: Boolean? = null
        val decision = startUpdateIfReady({ emptyList() }, {
            AdbUpdateStages.pendingTicket(UpdateStageLog.read(adbLog, "selfUpdateAdb").lastOrNull()) != null
        }, { error("Unexpected session") }) {
            recovery = it
        }
        assertEquals(true, decision.accepted)
        assertEquals(true, recovery)
    }

    @Test fun corruptAdbContainerDoesNotLookLikeMissingTicket() {
        val file = temp.newFile("self-update-adb.json").apply { writeText("{}") }
        assertThrows(UnreadableUpdateStageStore::class.java) { UpdateStageLog.read(file, "selfUpdateAdb") }
        assertEquals("{}", file.readText())
    }

    @Test fun stagedWriteRejectsSilentReplacementFailureWithExistingStartedFile() {
        val base = temp.newFile("self-update-adb.json").apply {
            writeText("""{"selfUpdateAdb":[{"stage":"started","atMs":1}]}""")
        }
        val pending = temp.newFile("self-update-adb.json.new")
        val content = """{"selfUpdateAdb":[{"stage":"staged","atMs":2,"bytes":10,"ticket":"00000000-0000-0000-0000-000000000000"}]}"""
            .encodeToByteArray()

        assertThrows(IllegalStateException::class.java) {
            UpdateStageLog.writeVerified(content, pending.outputStream(), { it.close() },
                { it.close() }, base::readBytes)
        }
        assertEquals("started", UpdateStageLog.read(base, "selfUpdateAdb").last().stage)

        UpdateStageLog.writeVerified(content, pending.outputStream(), {
            it.close()
            java.nio.file.Files.move(pending.toPath(), base.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }, { it.close() }, base::readBytes)
        assertEquals("00000000-0000-0000-0000-000000000000",
            AdbUpdateStages.pendingTicket(UpdateStageLog.read(base, "selfUpdateAdb").last()))
    }

    @Test fun recoveryFilesArePreservedWhenReadingStageStore() {
        val file = File(temp.root, "spike-result.json")
        val unfinished = temp.newFile("spike-result.json.new").apply { writeText("[]") }
        assertThrows(UnreadableUpdateStageStore::class.java) { UpdateStageLog.read(file) }

        val backup = temp.newFile("spike-result.json.bak").apply { writeText("[]") }
        assertEquals(emptyList<UpdateStageRecord>(), UpdateStageLog.read(file))
        assertEquals("[]", backup.readText())
        assertEquals("[]", unfinished.readText())
        assertEquals(false, file.exists())
    }

    @Test fun shizukuSessionSurvivesRestartAndPendingUserAction() {
        val commit = UpdateStageRecord("commit-sent", 100, mapOf(
            "sessionId" to JsonPrimitive(42),
            "deadlineAtMs" to JsonPrimitive(180_100L),
        ))
        val userAction = UpdateStageRecord("pending-user-action", 200)
        for (stages in listOf(listOf(commit), listOf(commit, userAction))) {
            val restored = UpdateStageLog.decode(UpdateStageLog.encode(stages))
            val pending = checkNotNull(ShizukuUpdateSession.pending(restored))
            assertEquals(42, pending.id)
            assertEquals(180_100L, pending.deadlineAtMs)
            assertNull(pending.expired(180_099))
            val result = checkNotNull(pending.expired(180_100))
            assertEquals("result", result.stage)
            assertEquals(JsonPrimitive("unknown"), result.fields["outcome"])
            assertEquals(JsonPrimitive(42), result.fields["sessionId"])
            assertNull(ShizukuUpdateSession.pending(restored + result))
        }
    }

    @Test fun terminalShizukuCallbackClearsPersistedSession() {
        val commit = UpdateStageRecord("commit-sent", 100, mapOf(
            "sessionId" to JsonPrimitive(42),
            "deadlineAtMs" to JsonPrimitive(180_100L),
        ))
        val callback = UpdateStageRecord("result", 200, mapOf(
            "sessionId" to JsonPrimitive(42),
            "status" to JsonPrimitive(0),
        ))
        val restored = UpdateStageLog.decode(UpdateStageLog.encode(listOf(commit, callback)))
        assertNull(ShizukuUpdateSession.pending(restored))
    }

    @Test fun persistsStagesAndDerivesResultFromInstallerOutput() {
        val records = listOf(
            AdbUpdateStages.started(1),
            AdbUpdateStages.staged(2, 100, "00000000-0000-0000-0000-000000000000"),
        ) + checkNotNull(AdbUpdateStages.completed(3, "Success\n\n__rc=0\n"))
        val decoded = UpdateStageLog.decode(UpdateStageLog.encode(records))
        assertEquals(records, decoded)
        val status = UpdateStageLog.statusJson(decoded, 4, false)
        assertEquals(listOf("started", "staged", "install-output", "result"), decoded.map { it.stage })
        assertEquals(JsonPrimitive(true), (status["current"] as JsonObject)["success"])
    }

    @Test fun successRequiresZeroExitCodeAndAnExactLine() {
        val cases = listOf(
            Triple("Success", 0, true),
            Triple("Success", 1, false),
            Triple("Failure [Success could not be confirmed]", 0, false),
            Triple(" Success ", 0, false),
            Triple("Failure [INSTALL_FAILED]", 1, false),
        )
        for ((output, exitCode, success) in cases) {
            val result = checkNotNull(AdbUpdateStages.completed(3, "$output\n__rc=$exitCode\n")).last()
            assertEquals(JsonPrimitive(success), result.fields["success"])
            assertEquals(JsonPrimitive(exitCode), result.fields["exitCode"])
        }
        assertNull(AdbUpdateStages.completed(3, "Success\n"))
    }

    @Test fun unavailableResultExpiresAndClearsPendingTicket() = runBlocking {
        val staged = AdbUpdateStages.staged(100, 10, "00000000-0000-0000-0000-000000000000")
        var now = 100L
        var probes = 0
        val terminal = awaitAdbUpdateResult(staged, now = { now }, pause = { now += it }) {
            probes++
            if (probes == 1) null else throw java.io.IOException("read failed")
        }.last()
        assertEquals("result", terminal.stage)
        assertEquals(JsonPrimitive("unknown"), terminal.fields["outcome"])
        assertNull(terminal.fields["success"])
        assertNull(AdbUpdateStages.pendingTicket(terminal))
        assertEquals(true, probes > 1)
    }

    @Test fun alreadyExpiredTicketDoesNotProbe() = runBlocking {
        val staged = AdbUpdateStages.staged(100, 10, "00000000-0000-0000-0000-000000000000")
        val terminal = awaitAdbUpdateResult(staged, now = { 180_100L }) {
            error("Expired tickets must not reconnect")
        }.last()
        assertEquals(JsonPrimitive("unknown"), terminal.fields["outcome"])
    }

    @Test fun lateResultCannotTurnAnExpiredLaunchIntoSuccess() = runBlocking {
        val staged = AdbUpdateStages.staged(100, 10, "00000000-0000-0000-0000-000000000000")
        val launch = AdbUpdateStages.launch(200, staged, null, "ADB shell timed out")
        assertEquals("launch-unknown", launch.stage)
        assertEquals(AdbUpdateStages.pendingTicket(staged), AdbUpdateStages.pendingTicket(launch))
        var now = 180_099L
        val terminal = awaitAdbUpdateResult(launch, now = { now }) {
            now++
            "Success\n__rc=0\n"
        }.last()
        assertEquals(JsonPrimitive("unknown"), terminal.fields["outcome"])
    }
}
