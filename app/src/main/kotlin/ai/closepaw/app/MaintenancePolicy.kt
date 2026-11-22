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

internal enum class ShizukuHealAction { RUN_STARTER, KILL_THEN_STARTER, ABORT }

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

        fun healAction(
            diagnosed: ShizukuServerIdentity,
            current: ShizukuServerIdentity?,
        ): ShizukuHealAction = when {
            current == null -> ShizukuHealAction.ABORT
            current == ShizukuServerIdentity.Absent -> ShizukuHealAction.RUN_STARTER
            current == diagnosed -> ShizukuHealAction.KILL_THEN_STARTER
            else -> ShizukuHealAction.ABORT
        }

        fun postKillAction(observed: ShizukuServerIdentity?): ShizukuHealAction =
            if (observed == ShizukuServerIdentity.Absent) ShizukuHealAction.RUN_STARTER
            else ShizukuHealAction.ABORT

        fun buildHealCommand(identity: ShizukuServerIdentity, startCommand: String): String =
            "current=\$($IDENTITY_PROBE_COMMAND); " + when (identity) {
                ShizukuServerIdentity.Absent ->
                    "case \"\$current\" in ABSENT) $startCommand;; *) echo $HEAL_ABORT_MARKER;; esac"
                is ShizukuServerIdentity.Present ->
                    "proceed=; case \"\$current\" in ABSENT) proceed=1;; " +
                        "'${identity.pid}'\\ '${identity.startTicks}') kill -9 ${identity.pid} 2>/dev/null; " +
                        "n=0; while [ \$n -lt 20 ]; do pidof shizuku_server >/dev/null 2>&1 || break; " +
                        "n=\$((n+1)); sleep 0.1; done; " +
                        "after=\$($IDENTITY_PROBE_COMMAND); " +
                        "case \"\$after\" in ABSENT) proceed=1;; *) echo $HEAL_ABORT_MARKER;; esac;; " +
                        "*) echo $HEAL_ABORT_MARKER;; esac; " +
                        "if [ -n \"\$proceed\" ]; then $startCommand; fi"
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
