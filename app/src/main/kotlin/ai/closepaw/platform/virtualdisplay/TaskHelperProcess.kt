package ai.closepaw.platform.virtualdisplay

import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.taskhelper.TaskHelperProtocol
import android.content.Context
import android.os.Process
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal class TaskHelperProcess(private val shell: AdbShellChannel) {
    private val name = "closepaw-vd-${TaskHelperProtocol.newChallenge().take(DIRECTORY_SUFFIX_LENGTH)}"
    private val directory = "/data/local/tmp/$name"
    private val dex = "$directory/helper.dex"
    private val pidFile = "$directory/pid"
    private val goFile = "$directory/go"
    private val guard = TaskHelperProtocol.LaunchGuard()

    val directoryPath: String get() = directory

    var pending = false
        private set

    suspend fun start(context: Context, challenge: String, onPid: (Int) -> Unit) {
        connectAsShell()
        pending = true
        stagingMutex.withLock {
            command("umask 077; mkdir $directory && printf '%s\\n' '$PID_FILE_PENDING' > $pidFile")
            try {
                val local = File.createTempFile("closepaw-task-helper-", ".dex", context.cacheDir)
                try {
                    context.assets.open(TaskHelperSession.DEX_ASSET).use { input ->
                        local.outputStream().use { input.copyTo(it) }
                    }
                    local.inputStream().use { bytes ->
                        withTimeout(UPLOAD_TIMEOUT_MS) { shell.writeToRemote(dex, bytes, local.length()) }
                    }
                } finally {
                    local.delete()
                }

                val authority = quote(context.packageName + TaskHelperProtocol.AUTHORITY_SUFFIX)
                val registration = quote(TaskHelperProtocol.encodeRegistration(challenge))
                val launch = "exec env CLASSPATH=$dex /system/bin/app_process /system/bin " +
                        "--nice-name=$name ai.closepaw.taskhelper.TaskHelperMain " +
                        "$authority $registration ${context.applicationInfo.uid} ${Process.myPid()}"
                // The detached shell keeps its PID across exec. It cannot exec before the app records it.
                val gatedLaunch = """
                    for attempt in $(seq 1 $GATE_POLLS); do
                        [ -d $directory ] || exit 1
                        if [ -f $goFile ]; then
                            $launch
                        fi
                        sleep $POLL_SECONDS
                    done
                    rm -rf $directory
                    exit 1
                """.trimIndent()
                val pid = command(
                        "umask 077; chmod 400 $dex && { " +
                                "nohup /system/bin/sh -c ${quote(gatedLaunch)} $name </dev/null >$directory/log 2>&1 & child=\$!; " +
                                "printf '%s\\n' \"\$child\" >$pidFile && printf '%s\\n' \"\$child\"; }",
                ).trim().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IOException("Task helper launch did not return a PID")
                guard.recordPid(pid)
                onPid(pid)
                // A lost reply to this command may still mean app_process started.
                guard.enable()
                command("umask 077; : > $goFile")
            } catch (failure: Throwable) {
                runCatching { cleanup() }
                throw failure
            }
        }
    }

    suspend fun cleanup() {
        if (!pending) return
        connectAsShell()
        val expectedPid = guard.pidToVerify()
        // Before the gate opens, a lost launch response can leave a waiting shell. Recover its
        // PID when available and remove the directory to revoke the gate on every path.
        val result = command(
                """
                pid=$expectedPid
                if [ "${'$'}pid" = 0 ] && [ -f $pidFile ]; then
                    pid=$(cat $pidFile)
                fi
                # A corrupt pid record (non-numeric content other than the
                # pending sentinel) leaves the directory and the record
                # exactly as found: cleanup stays unconfirmed and a retry
                # sees the same state, so a corrupt record can never drift
                # into a false GONE on a later attempt. Settlement of a
                # corrupt record is the sweep's grace rule, never cleanup
                # retry drift.
                case "${'$'}pid" in
                    *[!0-9]*) [ "${'$'}pid" = pending ] || exit 1 ;;
                esac
                # No pid was ever recorded (0, empty, or the pending
                # sentinel): the gate cannot have let a helper into
                # app_process, and removing the staging directory revokes
                # the gate for a shell still waiting on it. No pid
                # operation is performed on the sentinel. Staging removal
                # settles cleanup.
                case "${'$'}pid" in
                    0|''|pending)
                        rm -rf $directory && [ ! -e $directory ] || exit 1
                        printf '%s\n' GONE
                        exit 0
                        ;;
                esac
                proc=/proc/${'$'}pid
                if [ ! -d "${'$'}proc" ]; then
                    # The recorded process is already gone.
                    rm -rf $directory && [ ! -e $directory ] || exit 1
                    printf '%s\n' GONE
                    exit 0
                fi
                cmdline=$(tr '\000' '\n' < "${'$'}proc/cmdline" 2>/dev/null)
                read_status=${'$'}?
                if [ "${'$'}read_status" -ne 0 ]; then
                    # UNREADABLE: the read itself failed. Any partial output
                    # is untrustworthy and must never reach the identity
                    # comparison. Leave everything untouched and stay
                    # unconfirmed so the pair stays enrolled for the reaper.
                    exit 1
                fi
                if [ -z "${'$'}cmdline" ]; then
                    if [ ! -d "${'$'}proc" ]; then
                        rm -rf $directory && [ ! -e $directory ] || exit 1
                        printf '%s\n' GONE
                        exit 0
                    fi
                    stat=$(cat "${'$'}proc/stat" 2>/dev/null)
                    if [ -z "${'$'}stat" ]; then
                        if [ ! -d "${'$'}proc" ]; then
                            rm -rf $directory && [ ! -e $directory ] || exit 1
                            printf '%s\n' GONE
                            exit 0
                        fi
                        exit 1
                    fi
                    # The state follows the last ')' because comm can
                    # contain spaces and parentheses.
                    after=${'$'}{stat##*)}
                    case "${'$'}after" in
                        ' Z '*)
                            # A zombie's cmdline read succeeds with empty
                            # output. State Z confirms it is dead and only
                            # awaiting reap, so cleanup settles. A failed
                            # read alone proves nothing.
                            rm -rf $directory && [ ! -e $directory ] || exit 1
                            printf '%s\n' GONE
                            exit 0
                            ;;
                    esac
                    # UNREADABLE: the process can still be alive with no
                    # readable identity. Stay unconfirmed.
                    exit 1
                fi
                if printf '%s\n' "${'$'}cmdline" | grep -Fqx -e '$name' -e '--nice-name=$name' -e 'CLASSPATH=$dex'; then
                    # MATCH: the recorded pid is the helper. Kill it and
                    # poll for its exit; settle only once the exit is
                    # observed and staging is removed.
                    kill -KILL "${'$'}pid" 2>/dev/null
                    for attempt in $(seq 1 $EXIT_POLLS); do
                        if [ ! -d "${'$'}proc" ] && ! kill -0 "${'$'}pid" 2>/dev/null; then
                            rm -rf $directory && [ ! -e $directory ] || exit 1
                            printf '%s\n' GONE
                            exit 0
                        fi
                        sleep $POLL_SECONDS
                    done
                    # Killed but its exit is unconfirmed: leave the
                    # directory and the pid record in place so a retry
                    # re-verifies the same pid instead of losing it.
                    exit 1
                fi
                # MISMATCH: a readable cmdline that does not identify the
                # helper — the pid was recycled by an unrelated process,
                # which must never be killed or waited on. Staging removal
                # settles cleanup.
                rm -rf $directory && [ ! -e $directory ] || exit 1
                printf '%s\n' GONE
                exit 0
                """.trimIndent(),
        )
        check(result.trim() == "GONE") { "Task helper exit unconfirmed for $directory" }
        pending = false
    }

    suspend fun sweepStaleDirectories(): List<String> = stagingMutex.withLock {
        connectAsShell()
        val result = command(
                """
                now=$(date +%s)
                for directory in /data/local/tmp/closepaw-vd-*; do
                    [ -d "${'$'}directory" ] || continue
                    pid=$(cat "${'$'}directory/pid" 2>/dev/null)
                    # A usable pid is all digits with at least one nonzero
                    # digit, at most 7 significant digits, and a value at
                    # most 4194304. Corrupt records like 12x, x7, and
                    # whitespace-padded values have no usable pid.
                    usable=0
                    case "${'$'}pid" in
                        ''|*[!0-9]*) ;;
                        *[1-9]*)
                            # All digits with a nonzero digit is not enough:
                            # the value must be a plausible pid. Leading
                            # zeros are stripped first, so the bounds are
                            # judged on the significant digits: 00000007 is
                            # pid 7 and reaches the live check below, however
                            # old its directory is. (An all-zeros record has
                            # no nonzero digit, never enters this branch,
                            # and keeps the grace path — the same outcome
                            # as stripping it to empty.) This branch
                            # guarantees a nonzero digit, so the stripped
                            # value is nonempty. The length bound is checked
                            # on the stripped value BEFORE any numeric
                            # comparison, so an overlong digit string never
                            # reaches arithmetic. 007 is pid 7 and usable;
                            # 4194305, 9999999, and every record with 8+
                            # significant digits have no usable pid and
                            # take the grace path below.
                            value=${'$'}pid
                            while [ "${'$'}value" != "${'$'}{value#0}" ]; do value=${'$'}{value#0}; done
                            if [ "${'$'}{#value}" -le 7 ] && [ "${'$'}value" -le 4194304 ]; then
                                pid=${'$'}value
                                usable=1
                            fi
                            ;;
                        *) ;;
                    esac
                    if [ "${'$'}usable" -eq 1 ]; then
                        # A real pid: remove only once it is dead. A
                        # live pid's directory is never swept, however
                        # old it is.
                        kill -0 "${'$'}pid" 2>/dev/null || rm -rf "${'$'}directory"
                    else
                        # No usable pid (missing file, empty, the
                        # pending sentinel, corrupt content, or zero):
                        # remove only once the directory is older than
                        # the grace bound, so a staging directory whose
                        # launch is still in flight is never swept.
                        # Un-ageable directories are left.
                        mtime=$(stat -c %Y "${'$'}directory" 2>/dev/null)
                        case "${'$'}mtime" in
                            ''|*[!0-9]*) : ;;
                            *) if [ $((now - mtime)) -gt $STALE_GRACE_SECONDS ]; then rm -rf "${'$'}directory"; fi ;;
                        esac
                    fi
                    if [ -d "${'$'}directory" ]; then
                        printf 'LEFT %s\n' "${'$'}directory"
                    fi
                done
                """.trimIndent(),
        )
        result.lineSequence()
                .filter { it.startsWith("LEFT ") }
                .map { it.removePrefix("LEFT ") }
                .toList()
    }

    private suspend fun connectAsShell() {
        check(shell.isConnected() || withTimeout(CONNECT_TIMEOUT_MS) { shell.connectAuto() }) {
            "Task helper ADB connection unavailable"
        }
        check(command("id -u").trim() == TaskHelperProtocol.SHELL_UID.toString()) {
            "Task helper ADB is not running as shell"
        }
    }

    private suspend fun command(script: String): String {
        val result = withTimeout(COMMAND_BOUND_MS) { shell.exec(script, timeoutMs = COMMAND_TIMEOUT_MS) }
        if (result.exitCode != 0 || result.truncated) {
            throw IOException("Task helper shell command failed (exit=${result.exitCode}, truncated=${result.truncated})")
        }
        return result.stdout
    }

    private fun quote(value: String): String {
        require('\u0000' !in value)
        return "'" + value.replace("'", "'\\''") + "'"
    }

    companion object {
        /**
         * Serializes staging creation against stale-directory sweeps across ALL
         * TaskHelperProcess instances (sessions are separate objects; an
         * instance-local lock cannot keep one session's sweep out of another
         * session's mkdir → pid-file window).
         */
        private val stagingMutex = Mutex()

        /** Written into the pid file by the same shell operation that creates the
         * staging directory, so the file exists from the first instant; the launch
         * command overwrites it with the real pid. */
        private const val PID_FILE_PENDING = "pending"

        /** A staging directory with no usable pid is swept only when older than
         * this: comfortably longer than any start sequence (connect + upload +
         * rendezvous), so an in-flight start is never swept. */
        private const val STALE_GRACE_SECONDS = 300

        private const val DIRECTORY_SUFFIX_LENGTH = 24
        private const val GATE_POLLS = 100
        private const val EXIT_POLLS = 10
        private const val POLL_SECONDS = "0.1"
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val UPLOAD_TIMEOUT_MS = 30_000L
        private const val COMMAND_BOUND_MS = 8_000L
        private const val COMMAND_TIMEOUT_MS = 6_000L
    }
}
