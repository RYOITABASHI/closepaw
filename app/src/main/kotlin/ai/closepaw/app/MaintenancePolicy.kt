package ai.closepaw.app

internal enum class AccessibilityRecoveryPlan {
    NO_OP, APPEND, TOGGLE_REBIND;

    companion object {
        // The setting may be missing even while an old binding is still alive.
        fun decide(settingContainsAgent: Boolean, bound: Boolean): AccessibilityRecoveryPlan = when {
            !settingContainsAgent -> APPEND
            bound -> NO_OP
            else -> TOGGLE_REBIND
        }
    }
}

internal enum class MaintenanceScanGate {
    CLAIM, SKIP, JOIN;

    companion object {
        private const val SCAN_INTERVAL_MS = 60_000L

        fun decide(nowMs: Long, lastScanMs: Long?, inFlight: Boolean): MaintenanceScanGate = when {
            inFlight -> JOIN
            lastScanMs != null && nowMs - lastScanMs < SCAN_INTERVAL_MS -> SKIP
            else -> CLAIM
        }
    }
}

internal sealed interface ShizukuServerIdentity {
    data object Absent : ShizukuServerIdentity
    data class Present(val pid: Int, val startTicks: Long) : ShizukuServerIdentity
}

internal enum class ShizukuHealDisposition {
    START_SERVER,
    ALIVE_BUT_UNREACHABLE,
    START_ROUTE_MISSING,
    PROBE_UNKNOWN,
}

internal const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
internal const val SHIZUKU_START_SCRIPT_PATH = "/sdcard/Android/data/$SHIZUKU_PACKAGE/start.sh"

/**
 * How the server is started. EXTRACTED_LIBS runs app_process directly with the installer-extracted
 * native libraries (the starter's `base.apk!/lib` path cannot load DEFLATED libraries).
 * START_SCRIPT is kept only for installs whose libraries were not extracted.
 */
internal enum class ShizukuStartRoute(val code: String) {
    EXTRACTED_LIBS("extracted_libs"),
    START_SCRIPT("start_script"),
}

internal sealed interface ShizukuRouteProbe {
    /** [installKey] is the Shizuku codePath; it changes whenever the APK is replaced. */
    data class Available(val route: ShizukuStartRoute, val installKey: String?) : ShizukuRouteProbe
    data object Missing : ShizukuRouteProbe
}

internal sealed interface ShizukuStartOutcome {
    data class Started(val pid: Int) : ShizukuStartOutcome
    data object IdentityAborted : ShizukuStartOutcome
    data object RouteGone : ShizukuStartOutcome
    /** The new server died with the deflated-library signature. Not retryable until the APK changes. */
    data class TerminalLinkError(val installKey: String?) : ShizukuStartOutcome
    data class Died(val starterFatal: Boolean) : ShizukuStartOutcome
    data object Unparsed : ShizukuStartOutcome
}

internal enum class ShizukuHealPlan {
    NO_OP, START;

    companion object {
        private const val MIN_ATTEMPT_INTERVAL_MS = 5 * 60_000L
        internal const val MAX_ATTEMPTS_PER_HOUR = 4
        internal const val MAX_PING_AGE_MS = 2_000L
        const val HEAL_ABORT_MARKER = "HEAL_ABORT_IDENTITY"

        // Strip through the last ") " so spaces and parentheses in comm do not shift starttime.
        const val IDENTITY_PROBE_COMMAND: String =
            "pid=\$(pidof shizuku_server 2>/dev/null); " +
                "case \"\$?:\$pid\" in 1:) echo ABSENT; exit 0;; 0:*) ;; *) echo UNKNOWN; exit 0;; esac; " +
                "case \"\$pid\" in ''|0|*[!0-9]*) echo UNKNOWN; exit 0;; esac; " +
                "ticks=\$(sed -n 's/^.*) //p' \"/proc/\$pid/stat\" 2>/dev/null | awk '{print \$20}'); " +
                "case \"\$ticks\" in ''|*[!0-9]*) echo UNKNOWN;; *) echo \"\$pid \$ticks\";; esac"

        fun parseServerIdentity(output: String): ShizukuServerIdentity? {
            val value = output.trim()
            if (value == "ABSENT") return ShizukuServerIdentity.Absent
            val fields = value.split(Regex("\\s+"))
            if (fields.size != 2) return null
            val pid = fields[0].toIntOrNull() ?: return null
            val startTicks = fields[1].toLongOrNull() ?: return null
            if (pid <= 0 || startTicks < 0) return null
            return ShizukuServerIdentity.Present(pid, startTicks)
        }

        // identity is the tri-state serverProcessAlive input: Present = alive, Absent = dead, null = unknown.
        fun disposition(
            identity: ShizukuServerIdentity?,
            startRoutePresent: Boolean?,
        ): ShizukuHealDisposition = when (identity) {
            null -> ShizukuHealDisposition.PROBE_UNKNOWN
            is ShizukuServerIdentity.Present -> ShizukuHealDisposition.ALIVE_BUT_UNREACHABLE
            ShizukuServerIdentity.Absent -> when (startRoutePresent) {
                null -> ShizukuHealDisposition.PROBE_UNKNOWN
                true -> ShizukuHealDisposition.START_SERVER
                false -> ShizukuHealDisposition.START_ROUTE_MISSING
            }
        }

        const val ROUTE_GONE_MARKER = "HEAL_ROUTE_GONE"
        const val STARTED_MARKER = "HEAL_STARTED"
        const val TERMINAL_LINK_MARKER = "HEAL_TERMINAL_LINK"
        const val DIED_MARKER = "HEAL_DIED"
        const val STARTER_FATAL_MARKER = "HEAL_STARTER_FATAL"

        /** Only Shizuku's own native-library failure is terminal, never another app's crash. */
        const val TERMINAL_LINK_SIGNATURE =
            "UnsatisfiedLinkError.*(moe\\.shizuku\\.privileged\\.api|librish\\.so|libshizuku\\.so)"
        internal const val TERMINAL_RECHECK_INTERVAL_MS = 5 * 60_000L

        /**
         * Sets `cp` to Shizuku's codePath and `lib` to an extracted library directory holding
         * libshizuku.so (empty when none). dumpsys is retried because `pm`-backed lookups flake
         * under load (diagnosis 2026-11-23, cause 3).
         */
        const val RESOLVE_INSTALL_FRAGMENT: String =
            "cp=; for i in 1 2 3; do " +
                "cp=\$(dumpsys package $SHIZUKU_PACKAGE 2>/dev/null | sed -n 's/^ *codePath=//p' | head -n 1); " +
                "[ -n \"\$cp\" ] && break; sleep 1; done; " +
                "lib=; case \"\$cp\" in /data/app/*) " +
                "if [ -f \"\$cp/base.apk\" ]; then for abi in arm64 x86_64 arm x86; do " +
                "if [ -f \"\$cp/lib/\$abi/libshizuku.so\" ]; then lib=\"\$cp/lib/\$abi\"; break; fi; " +
                "done; fi;; esac"

        const val START_ROUTE_PROBE_COMMAND: String =
            "$RESOLVE_INSTALL_FRAGMENT; " +
                "if [ -n \"\$lib\" ]; then echo \"EXTRACTED \$cp\"; " +
                "elif [ -f $SHIZUKU_START_SCRIPT_PATH ]; then echo \"SCRIPT \$cp\"; " +
                "else echo MISSING; fi"

        fun parseStartRoute(output: String): ShizukuRouteProbe? {
            val value = output.trim()
            if (value == "MISSING") return ShizukuRouteProbe.Missing
            val route = when (value.substringBefore(' ')) {
                "EXTRACTED" -> ShizukuStartRoute.EXTRACTED_LIBS
                "SCRIPT" -> ShizukuStartRoute.START_SCRIPT
                else -> return null
            }
            if (value.contains('\n')) return null
            val key = value.substringAfter(' ', "").trim().takeIf { it.startsWith("/data/app/") }
            if (route == ShizukuStartRoute.EXTRACTED_LIBS && key == null) return null
            return ShizukuRouteProbe.Available(route, key)
        }

        private fun startFragment(route: ShizukuStartRoute): String = when (route) {
            // setsid detaches the server from the adb shell session (R14 drill C, measured 2026-11-24).
            ShizukuStartRoute.EXTRACTED_LIBS ->
                "[ -n \"\$lib\" ] || { echo $ROUTE_GONE_MARKER; exit 0; }; " +
                    EXTRACTED_START_COMMAND
            // start.sh exits 0 even when the starter fails, so its output is scanned instead.
            ShizukuStartRoute.START_SCRIPT ->
                "[ -f $SHIZUKU_START_SCRIPT_PATH ] || { echo $ROUTE_GONE_MARKER; exit 0; }; " +
                    "sh $SHIZUKU_START_SCRIPT_PATH </dev/null 8>&- 9>&- 2>&1 | grep -q '^fatal:' && echo $STARTER_FATAL_MARKER"
        }

        /**
         * Detached server start from the extracted libraries. Lock fds 8/9 are closed so the
         * long-lived server never holds the start or watchdog lock.
         */
        const val EXTRACTED_START_COMMAND: String =
            "{ setsid /system/bin/app_process -Djava.class.path=\"\$cp/base.apk\" " +
                "-Dshizuku.library.path=\"\$lib\" /system/bin --nice-name=shizuku_server " +
                "rikka.shizuku.server.ShizukuService </dev/null >/dev/null 2>&1 8>&- 9>&- & }"

        /**
         * Shared by ClosePaw's H-1 and the layer-2 watchdog: whoever holds this flock (fd 8) is the
         * only one allowed to start a server, and re-checks that none exists after taking it.
         * Released automatically when the holder exits.
         */
        const val START_LOCK_FILE = "/data/local/tmp/closepaw-shizuku-start.lock"

        /** toybox flock has no -w: poll -n for up to 10 s. Sets lk=1 when fd 8 is locked. */
        const val ACQUIRE_START_LOCK: String =
            "lk=0; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do " +
                "flock -n 8 && { lk=1; break; }; sleep 0.5; done"

        /**
         * Re-validates on the device, under the shared start lock, that no server exists, starts
         * one, and reports what happened. Success is judged by a live server pid (and later the
         * binder), never by an exit code. A live server is never killed.
         */
        fun buildHealCommand(route: ShizukuStartRoute): String =
            "{ $ACQUIRE_START_LOCK; [ \"\$lk\" = 1 ] || { echo $HEAL_ABORT_MARKER; exit 0; }; " +
                "current=\$($IDENTITY_PROBE_COMMAND) || current=; " +
                "case \"\$current\" in ABSENT) ;; *) echo $HEAL_ABORT_MARKER; exit 0;; esac; " +
                "$RESOLVE_INSTALL_FRAGMENT; " +
                "t0=\$(date '+%m-%d %H:%M:%S.000'); " +
                "${startFragment(route)}; " +
                "sleep 3; pid=\$(pidof shizuku_server 2>/dev/null); " +
                "if [ -n \"\$pid\" ]; then echo \"$STARTED_MARKER \$pid\"; " +
                "elif logcat -b crash -d -T \"\$t0\" 2>/dev/null | grep -qE '$TERMINAL_LINK_SIGNATURE'; " +
                "then echo \"$TERMINAL_LINK_MARKER \$cp\"; " +
                "else echo $DIED_MARKER; fi; } 8>>$START_LOCK_FILE"

        fun parseStartOutcome(output: String): ShizukuStartOutcome {
            val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (HEAL_ABORT_MARKER in lines) return ShizukuStartOutcome.IdentityAborted
            if (ROUTE_GONE_MARKER in lines) return ShizukuStartOutcome.RouteGone
            lines.firstOrNull { it.startsWith("$STARTED_MARKER ") }?.let { line ->
                val pid = line.removePrefix("$STARTED_MARKER ").trim().split(Regex("\\s+")).firstOrNull()
                    ?.toIntOrNull()
                return if (pid != null && pid > 0) ShizukuStartOutcome.Started(pid) else ShizukuStartOutcome.Unparsed
            }
            lines.firstOrNull { it == TERMINAL_LINK_MARKER || it.startsWith("$TERMINAL_LINK_MARKER ") }?.let { line ->
                val key = line.removePrefix(TERMINAL_LINK_MARKER).trim().takeIf { it.startsWith("/data/app/") }
                return ShizukuStartOutcome.TerminalLinkError(key)
            }
            if (DIED_MARKER in lines) return ShizukuStartOutcome.Died(starterFatal = STARTER_FATAL_MARKER in lines)
            return ShizukuStartOutcome.Unparsed
        }

        /**
         * While a terminal failure is recorded, the install is re-probed at most every
         * [TERMINAL_RECHECK_INTERVAL_MS] and the probe does not consume start attempts.
         */
        fun terminalRecheckDue(terminalRecorded: Boolean, nowMs: Long, lastTerminalCheckMs: Long?): Boolean =
            !terminalRecorded || lastTerminalCheckMs == null || nowMs - lastTerminalCheckMs >= TERMINAL_RECHECK_INTERVAL_MS

        /** A recorded terminal failure blocks starts only while the same install is present. */
        fun startBlockedByTerminal(terminalKey: String?, installKey: String?): Boolean =
            terminalKey != null && installKey != null && terminalKey == installKey

        fun shouldDispatch(
            cachedBinderAlive: Boolean,
            pingAgeMs: Long,
            maxPingAgeMs: Long,
            vdSessionActive: Boolean,
        ): Boolean =
            !cachedBinderAlive && pingAgeMs <= maxPingAgeMs && !vdSessionActive

        fun decide(
            binderAlive: Boolean,
            vdSessionActive: Boolean,
            channelPaired: Boolean,
            attemptsInLastHour: Int,
            msSinceLastAttempt: Long?,
        ): ShizukuHealPlan = when {
            binderAlive -> NO_OP
            vdSessionActive -> NO_OP
            !channelPaired -> NO_OP
            msSinceLastAttempt != null && msSinceLastAttempt < MIN_ATTEMPT_INTERVAL_MS -> NO_OP
            attemptsInLastHour >= MAX_ATTEMPTS_PER_HOUR -> NO_OP
            else -> START
        }
    }
}

private const val ATTEMPT_WINDOW_MS = 60 * 60_000L

internal fun attemptsInLastHour(attempts: Iterable<Long>, nowMs: Long): Int =
    attempts.count { nowMs - it < ATTEMPT_WINDOW_MS }

/**
 * Heal v2 H-3: last-resort self restart when the Shizuku server is alive but this process never
 * received its binder (the server only hands binders to apps started after it).
 */
internal enum class SelfRestartDecision(val code: String) {
    DISPATCH("dispatch"),
    SKIP_ACTIVE_RUNS("active_runs"),
    SKIP_VD_BUSY("vd_busy"),
    SKIP_ADB("adb_unavailable"),
    SKIP_INTERVAL("interval"),
    SKIP_BUDGET("budget"),
}

/**
 * Refuses new MCP session starts while a self restart is pending. Engaged under the session
 * ownership lock together with the idleness check, so no start can slip in between.
 */
internal object SelfRestartLatch {
    @Volatile private var engagedUntilElapsedMs = 0L

    fun engage(nowElapsedMs: Long, holdMs: Long) {
        engagedUntilElapsedMs = nowElapsedMs + holdMs
    }

    fun release() {
        engagedUntilElapsedMs = 0L
    }

    fun isEngaged(nowElapsedMs: Long): Boolean = nowElapsedMs < engagedUntilElapsedMs
}

internal object SelfRestartPlan {
    const val MIN_INTERVAL_MS = 30 * 60_000L
    const val MAX_PER_DAY = 2
    const val DAY_MS = 24 * 60 * 60_000L
    const val DISPATCHED_MARKER = "SELF_RESTART_DISPATCHED"

    /**
     * [historyWallMs] is persisted wall-clock time because the process does not survive the
     * restart. Entries in the future (clock moved back) count as recent, so a clock jump can
     * only make the gate stricter.
     */
    fun decide(
        activeRuns: Int,
        vdBusy: Boolean,
        adbConnected: Boolean,
        nowWallMs: Long,
        historyWallMs: List<Long>,
    ): SelfRestartDecision {
        val recent = historyWallMs.filter { nowWallMs - it < DAY_MS }
        return when {
            activeRuns > 0 -> SelfRestartDecision.SKIP_ACTIVE_RUNS
            vdBusy -> SelfRestartDecision.SKIP_VD_BUSY
            !adbConnected -> SelfRestartDecision.SKIP_ADB
            recent.any { nowWallMs - it < MIN_INTERVAL_MS } -> SelfRestartDecision.SKIP_INTERVAL
            recent.size >= MAX_PER_DAY -> SelfRestartDecision.SKIP_BUDGET
            else -> SelfRestartDecision.DISPATCH
        }
    }

    fun parseHistory(value: String?): List<Long> =
        value.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    fun appendHistory(history: List<Long>, nowWallMs: Long): String =
        (history.filter { nowWallMs - it < DAY_MS } + nowWallMs).takeLast(MAX_PER_DAY * 2).joinToString(",")

    /**
     * The manual recovery sequence (force-stop, then rebind accessibility), run in a detached shell
     * session so it completes after this process dies. Other accessibility services are preserved;
     * nothing is launched in the foreground.
     */
    fun buildCommand(packageName: String, component: String, aliases: List<String> = emptyList()): String {
        val script = restartScript(packageName, component, aliases)
        // ClosePaw's channel closes the stream as soon as the exit marker arrives, and adbd then
        // hangs up the session. A plain `setsid … &` child can be hit before it has left the
        // session (D1, 2026-11-26). Double fork + nohup (SIGHUP ignored) + a short wait before
        // replying survived every early-close trial on the device.
        return "( nohup setsid sh -c ${shellQuote(script)} </dev/null >/dev/null 2>&1 & ) ; " +
            "sleep 1; echo $DISPATCHED_MARKER"
    }

    /** The bare restart sequence (no detaching); also run in-line by the layer-2 watchdog. */
    fun restartScript(packageName: String, component: String, aliases: List<String> = emptyList()): String {
        val componentPattern = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.\$]+")
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        require(component.matches(componentPattern) && aliases.all { it.matches(componentPattern) })
        val removals = (listOf(component) + aliases).joinToString(" ") { "-e ${shellQuote(it)}" }
        val key = "enabled_accessibility_services"
        // The pre-stop sleep lets the dispatch reply reach ClosePaw first; starts are latched meanwhile.
        return "sleep 2; am force-stop $packageName; sleep 1; " +
            "c=${shellQuote(component)}; " +
            "cur=\$(settings get secure $key); [ \"\$cur\" = null ] && cur=; " +
            "rest=\$(printf '%s\\n' \"\$cur\" | tr ':' '\\n' | grep -vx -F $removals -e '' | tr '\\n' ':'); " +
            "rest=\${rest%:}; " +
            "if [ -n \"\$rest\" ]; then settings put secure $key \"\$rest\"; " +
            "else settings delete secure $key; fi; " +
            "sleep 1; settings put secure $key \"\${rest:+\$rest:}\$c\"; " +
            "settings put secure accessibility_enabled 1"
    }

    internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * Heal layer 2: a self restart requested by the shell-uid watchdog through the credential
 * provider. Same gates and shared history as H-3, so the watchdog and ClosePaw's own H-3 can never
 * both restart within the interval. Returns the decision code sent back to the watchdog.
 */
internal object ExternalSelfRestartPlan {
    const val GO = "go"
    const val NOT_NEEDED = "not_needed"
    const val HEAL_IN_FLIGHT = "heal_in_flight"

    fun decide(
        binderAlive: Boolean,
        healInFlight: Boolean,
        activeRuns: Int,
        vdBusy: Boolean,
        nowWallMs: Long,
        historyWallMs: List<Long>,
    ): String = when {
        binderAlive -> NOT_NEEDED
        healInFlight -> HEAL_IN_FLIGHT
        // The watchdog runs its own shell, so ClosePaw's adb channel is not required here.
        else -> when (val d = SelfRestartPlan.decide(activeRuns, vdBusy, true, nowWallMs, historyWallMs)) {
            SelfRestartDecision.DISPATCH -> GO
            else -> d.code
        }
    }
}

/**
 * Heal layer 2 watchdog: a shell-uid loop, detached like H-3, that restarts a vanished Shizuku
 * server from the extracted libraries and then asks ClosePaw (shell-only `content call`) whether
 * to run the restart sequence itself, so recovery completes without ClosePaw's adb channel.
 * A live server is never killed. One instance at a time (pid file + cmdline check).
 */
internal object HealWatchdogPlan {
    const val NAME = "closepaw-heal-watchdog-v1"
    const val PID_FILE = "/data/local/tmp/closepaw-heal-watchdog.pid"
    const val LOG_FILE = "/data/local/tmp/closepaw-heal-watchdog.log"
    /** Held (flock on fd 9) for the watchdog's whole life; a second instance exits at once. */
    const val INSTANCE_LOCK_FILE = "/data/local/tmp/closepaw-heal-watchdog.lock"
    const val LOOP_S = 15
    /** Absent this long before the watchdog starts the server, leaving the first attempt to ClosePaw. */
    const val ABSENT_GRACE_S = 20
    /** Periodic check (in loops) for a live server whose binder never reached ClosePaw. */
    const val CHECK_EVERY_LOOPS = 20
    const val STARTED_MARKER = "WATCHDOG_STARTED"
    const val RUNNING_MARKER = "WATCHDOG_RUNNING"
    const val METHOD = "selfRestart"
    const val DECISION_KEY = "decision"

    fun script(restartScript: String, authority: String): String {
        require(authority.matches(Regex("[A-Za-z0-9_.]+")))
        return """
            {
            flock -n 9 || exit 0;
            PIDF=$PID_FILE; LOG=$LOG_FILE; echo §§ > "§PIDF";
            log() { echo "§(date +%m-%d_%H:%M:%S) §*" >> "§LOG"; if [ "§(wc -c < "§LOG")" -gt 65536 ]; then tail -c 32768 "§LOG" > "§LOG.t" && mv "§LOG.t" "§LOG"; fi; };
            ask() { r=§(content call --uri content://$authority --method $METHOD 2>&1); case "§r" in *$DECISION_KEY=${ExternalSelfRestartPlan.GO}*) log "selfRestart go (§1)"; $restartScript; log "restart sequence done";; *$DECISION_KEY=${ExternalSelfRestartPlan.NOT_NEEDED}*) ;; *) log "selfRestart §(echo "§r" | grep -o '$DECISION_KEY=[a-z_]*' | head -n 1) (§1)";; esac; };
            log "start pid=§§"; absent=0; n=0;
            while :; do
              if pidof shizuku_server >/dev/null 2>&1; then absent=0;
              else absent=§((absent+$LOOP_S));
                if [ "§absent" -ge $ABSENT_GRACE_S ]; then absent=0; started=0;
                  { ${ShizukuHealPlan.ACQUIRE_START_LOCK}; if [ "§lk" = 1 ]; then
                      ${ShizukuHealPlan.RESOLVE_INSTALL_FRAGMENT};
                      if [ -n "§lib" ] && ! pidof shizuku_server >/dev/null 2>&1; then
                        ${ShizukuHealPlan.EXTRACTED_START_COMMAND.replace("$", "§")};
                        sleep 4;
                        if pidof shizuku_server >/dev/null 2>&1; then started=1; log "shizuku started pid=§(pidof shizuku_server)"; else log "shizuku start failed"; fi;
                      else log "start skipped (no extracted libs or server back)"; fi;
                    else log "start lock busy"; fi; } 8>>${ShizukuHealPlan.START_LOCK_FILE};
                  if [ "§started" = 1 ]; then sleep 6; ask after_start; fi;
                fi;
              fi;
              n=§((n+1)); if [ "§n" -ge $CHECK_EVERY_LOOPS ]; then n=0; pidof shizuku_server >/dev/null 2>&1 && ask periodic; fi;
              sleep $LOOP_S;
            done;
            } 9>>$INSTANCE_LOCK_FILE
        """.trimIndent().replace("\n", " ").replace("§", "$")
    }

    /** Starts the watchdog unless one is already alive. Survives the channel's early close (H-3 form). */
    fun launchCommand(script: String): String =
        "p=\$(cat $PID_FILE 2>/dev/null); " +
            "if [ -n \"\$p\" ] && grep -q $NAME /proc/\$p/cmdline 2>/dev/null; then echo $RUNNING_MARKER; " +
            "else ( nohup setsid sh -c ${SelfRestartPlan.shellQuote(script)} $NAME </dev/null >/dev/null 2>&1 & ) ; " +
            "sleep 1; echo $STARTED_MARKER; fi"
}
