package ai.closepaw.update

import ai.closepaw.adb.AdbShellChannel
import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal object AdbSelfUpdate {
    private const val KEY = "selfUpdateAdb"
    private val stageLock = Any()

    fun records(context: Context): List<UpdateStageRecord> = synchronized(stageLock) {
        UpdateStageLog.read(stageFile(context), KEY)
    }

    fun started(context: Context) = save(context, listOf(AdbUpdateStages.started(now())), replace = true)

    suspend fun install(context: Context, adb: AdbShellChannel, apk: File, bytes: Long) {
        val ticket = UUID.randomUUID().toString()
        val apkPath = apkPath(ticket)
        val output = outputFile(ticket)
        val staged = try {
            apk.inputStream().use { adb.writeToRemote(apkPath, it, bytes) }
            AdbUpdateStages.staged(now(), bytes, ticket).also { save(context, listOf(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { fail(context, e) }
            runCatching { adb.exec("rm -f $apkPath") }
            return
        }

        // Android may kill this app during reinstall. Publish the pm status and output together.
        val worker = "umask 077; : > $output.started || exit 1; pm install -r $apkPath > $output.tmp 2>&1; rc=\$?; " +
            "rm -f $apkPath; printf \"\\n__rc=%s\\n\" \"\$rc\" >> $output.tmp && mv $output.tmp $output"
        val launch = try {
            val ack = adb.exec(
                "umask 077; trap \"\" HUP; " +
                    // The worker script uses double quotes only, so it can be passed as one shell argument.
                    "nohup sh -c '$worker' </dev/null >$output.launch 2>&1 &\n" +
                    "pid=\$!; tries=0; " +
                    "while [ ! -f $output.started ] && kill -0 \"\$pid\" 2>/dev/null && [ \"\$tries\" -lt 20 ]; do " +
                    "sleep 0.1; tries=\$((tries + 1)); done; " +
                    "if [ ! -f $output.started ]; then " +
                    "cat $output.launch; echo 'Installer startup was not confirmed'; exit 1; fi",
                timeoutMs = 5_000,
            )
            AdbUpdateStages.launch(now(), staged, ack.exitCode, ack.stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AdbUpdateStages.launch(now(), staged, null, UpdateStageLog.describe(e))
        }
        save(context, listOf(launch))
        collectResult(context, adb)
    }

    fun pending(context: Context): Boolean = AdbUpdateStages.pendingTicket(records(context).lastOrNull()) != null

    suspend fun collectResult(context: Context, adb: AdbShellChannel) {
        val stage = records(context).lastOrNull() ?: return
        val ticket = AdbUpdateStages.pendingTicket(stage) ?: return
        val output = outputFile(ticket)
        val terminal = try {
            collectAdbUpdateResult(stage, ::now) {
                if (!adb.isConnected()) throw AdbChannelUnavailable()
                val result = guardAdbResultRead(adb::isConnected) {
                    adb.exec("if [ -f $output ]; then cat $output; else exit 75; fi")
                }
                result.stdout.takeIf { result.exitCode == 0 && !result.truncated }
            }
        } catch (_: AdbChannelUnavailable) {
            // Keep the ticket for status recovery once the channel reconnects.
            Log.w("ClosePawMcpAudit", "self_update result collection deferred: adb_channel_unavailable")
            return
        }
        save(context, terminal)
        if (terminal.last().fields.containsKey("exitCode")) {
            runCatching {
                adb.exec("rm -f ${apkPath(ticket)} $output $output.tmp $output.started $output.launch", timeoutMs = 1_000)
            }
        }
    }

    fun fail(context: Context, error: Exception) {
        save(context, listOf(AdbUpdateStages.failed(now(), error)))
    }

    private fun apkPath(ticket: String) = "/data/local/tmp/closepaw-update-$ticket.apk"

    private fun outputFile(ticket: String) = "/data/local/tmp/closepaw-update-$ticket.result"

    private fun stageFile(context: Context) = File(context.filesDir, "self-update-adb.json")

    private fun save(context: Context, added: List<UpdateStageRecord>, replace: Boolean = false) =
        synchronized(stageLock) {
            val previous = records(context)
            val history = if (replace) added else previous + added
            val target = AtomicFile(stageFile(context))
            val content = "{\"$KEY\":${UpdateStageLog.encode(history)}}".encodeToByteArray()
            UpdateStageLog.writeVerified(content, target.startWrite(), target::finishWrite, target::failWrite, target::readFully)
        }

    private fun now() = System.currentTimeMillis()
}
