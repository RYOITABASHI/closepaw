package ai.closepaw.spike.vd

import android.content.Context
import android.os.Process
import ai.closepaw.adb.AdbShellChannel
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

internal class ResidentProcess(private val shell: AdbShellChannel) {
    private val name = "closepaw-vd-${VdProtocol.newChallenge().take(24)}"
    val directory = "/data/local/tmp/$name"
    private val dex = "$directory/helper.dex"
    private val log = "$directory/log"
    private val pidFile = "$directory/pid"
    private val goFile = "$directory/go"
    private val guard = VdProtocol.LaunchGuard()

    var pending = false
        private set

    suspend fun start(context: Context, challenge: String, onStaging: () -> Unit, onPid: (Int) -> Unit) {
        check(withTimeout(25_000) { shell.connectAuto() }) { "VD ADB connection unavailable" }
        check(command("id -u").trim() == VdProtocol.SHELL_UID.toString()) {
            "VD ADB is not running as shell"
        }
        pending = true
        onStaging()
        command("umask 077; mkdir $directory")
        val local = File.createTempFile("closepaw-vd-", ".dex", context.cacheDir)
        try {
            context.assets.open("closepaw-vd-helper-v1.dex").use { input ->
                local.outputStream().use { input.copyTo(it) }
            }
            local.inputStream().use { bytes ->
                withTimeout(30_000) { shell.writeToRemote(dex, bytes, local.length()) }
            }
        } finally {
            local.delete()
        }
        val authority = quote(context.packageName + VdProtocol.AUTHORITY_SUFFIX)
        val registration = quote(VdProtocol.encodeRegistration(challenge))
        val launch = "exec env CLASSPATH=$dex /system/bin/app_process /system/bin " +
            "--nice-name=$name ai.closepaw.spike.vd.VdResidentMain " +
            "$authority $registration ${context.applicationInfo.uid} ${Process.myPid()}"
        // The detached shell and app_process share a PID across exec. A failed PID
        // write or interrupted launch response leaves only this bounded waiting shell.
        val gatedLaunch = """
            for attempt in $(seq 1 100); do
                [ -d $directory ] || exit 1
                if [ -f $goFile ]; then
                    $launch
                fi
                sleep 0.1
            done
            exit 1
        """.trimIndent()
        val output = command("umask 077; chmod 400 $dex && { " +
            "nohup /system/bin/sh -c ${quote(gatedLaunch)} $name </dev/null >$log 2>&1 & child=\$!; " +
            "printf '%s\\n' \"\$child\" >$pidFile && printf '%s\\n' \"\$child\"; }")
        val pid = output.trim().toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("VD shell launch returned an invalid PID")

        guard.recordPid(pid)
        onPid(pid)
        // Record possible execution before contacting the shell. A lost reply
        // here must be handled as a launch that may have reached app_process.
        guard.enable()
        command("umask 077; : > $goFile")
    }

    suspend fun startupDiagnostic(): String = try {
        val detail = command("head -c 1024 $log").trim()
        if (detail.isEmpty()) "" else " ($detail)"
    } catch (_: Exception) {
        ""
    }

    suspend fun cleanup() {
        if (!pending) return
        try {
            check(shell.isConnected() || withTimeout(25_000) { shell.connectAuto() }) {
                "VD ADB connection unavailable for cleanup"
            }
            check(command("id -u").trim() == VdProtocol.SHELL_UID.toString()) {
                "VD cleanup is not running as shell"
            }
            val pid = guard.pidToVerify()
            val absent = pid > 0 && confirmExit(pid)
            check(guard.deathConfirmed(absent)) { "VD helper PID $pid may still be alive" }
            command("rm -rf $directory && [ ! -e $directory ]")
            pending = false
        } catch (failure: Exception) {
            throw IOException("VD cleanup UNCONFIRMED for $directory", failure)
        }
    }

    private suspend fun confirmExit(pid: Int): Boolean =
        command(
            """
            proc=/proc/$pid
            if [ -d "${'$'}proc" ] &&
                tr '\000' '\n' < "${'$'}proc/cmdline" |
                    grep -Fqx -e '$name' -e '--nice-name=$name' -e 'CLASSPATH=$dex'; then
                kill -KILL $pid 2>/dev/null
            fi
            for attempt in 1 2 3 4 5 6 7 8 9 10; do
                if [ ! -d "${'$'}proc" ] && ! kill -0 $pid 2>/dev/null; then
                    printf '%s\n' GONE
                    exit 0
                fi
                sleep 0.1
            done
            """.trimIndent(),
        ).trim() == "GONE"

    private suspend fun command(script: String): String {
        val result = withTimeout(8_000) { shell.exec(script, timeoutMs = 6_000) }
        if (result.exitCode != 0 || result.truncated) {
            // The script may contain the one-use registration challenge.
            throw IOException("VD shell command failed (exit=${result.exitCode}, truncated=${result.truncated})")
        }
        return result.stdout
    }

    private fun quote(value: String): String {
        require('\u0000' !in value)
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
