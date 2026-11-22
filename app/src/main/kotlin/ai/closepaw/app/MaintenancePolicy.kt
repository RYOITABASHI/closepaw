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

internal const val SHIZUKU_START_SCRIPT_PATH = "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh"

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

        const val START_ROUTE_PROBE_COMMAND =
            "if [ -f $SHIZUKU_START_SCRIPT_PATH ]; then echo PRESENT; else echo MISSING; fi"

        fun parseStartRoutePresent(output: String): Boolean? = when (output.trim()) {
            "PRESENT" -> true
            "MISSING" -> false
            else -> null
        }

        fun buildHealCommand(startCommand: String): String {
            require(startCommand == "sh $SHIZUKU_START_SCRIPT_PATH")
            return "current=\$($IDENTITY_PROBE_COMMAND) || current=; " +
                "case \"\$current\" in ABSENT) $startCommand;; *) echo $HEAL_ABORT_MARKER;; esac"
        }

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
