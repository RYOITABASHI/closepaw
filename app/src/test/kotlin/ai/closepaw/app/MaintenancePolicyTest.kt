package ai.closepaw.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MaintenancePolicyTest {
    @Test fun recoveryPlansFollowSettingAndBindState() {
        assertEquals(AccessibilityRecoveryPlan.APPEND, AccessibilityRecoveryPlan.decide(false, false))
        assertEquals(AccessibilityRecoveryPlan.APPEND, AccessibilityRecoveryPlan.decide(false, true))
        assertEquals(AccessibilityRecoveryPlan.NO_OP, AccessibilityRecoveryPlan.decide(true, true))
        assertEquals(AccessibilityRecoveryPlan.TOGGLE_REBIND, AccessibilityRecoveryPlan.decide(true, false))
    }

    @Test fun scanClaimsRespectTheWindowAndInFlightWork() {
        assertEquals(MaintenanceScanGate.CLAIM, MaintenanceScanGate.decide(0, null, false))
        assertEquals(MaintenanceScanGate.SKIP, MaintenanceScanGate.decide(59_999, 0, false))
        assertEquals(MaintenanceScanGate.JOIN, MaintenanceScanGate.decide(60_000, 0, true))
        assertEquals(MaintenanceScanGate.CLAIM, MaintenanceScanGate.decide(60_000, 0, false))
    }

    @Test fun shizukuHealRequiresADeadBinderAndIdleDisplay() {
        assertEquals(ShizukuHealPlan.NO_OP, ShizukuHealPlan.decide(true, false, true, 0, null))
        assertEquals(ShizukuHealPlan.NO_OP, ShizukuHealPlan.decide(false, true, true, 0, null))
        assertEquals(ShizukuHealPlan.NO_OP, ShizukuHealPlan.decide(false, false, false, 0, null))
    }

    @Test fun shizukuDispatchRequiresADeadBinderAndIdleDisplay() {
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = true, pingAgeMs = 0L, maxPingAgeMs = 2_000L, vdSessionActive = false,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = true, pingAgeMs = 0L, maxPingAgeMs = 2_000L, vdSessionActive = true,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = true, pingAgeMs = 2_001L, maxPingAgeMs = 2_000L, vdSessionActive = false,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = true, pingAgeMs = 2_001L, maxPingAgeMs = 2_000L, vdSessionActive = true,
            ),
        )
        assertEquals(
            true,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = false, pingAgeMs = 0L, maxPingAgeMs = 2_000L, vdSessionActive = false,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = false, pingAgeMs = 0L, maxPingAgeMs = 2_000L, vdSessionActive = true,
            ),
        )
        assertEquals(
            true,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = false, pingAgeMs = 2_000L, maxPingAgeMs = 2_000L, vdSessionActive = false,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = false, pingAgeMs = 2_000L, maxPingAgeMs = 2_000L, vdSessionActive = true,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = false, pingAgeMs = 2_001L, maxPingAgeMs = 2_000L, vdSessionActive = false,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = false, pingAgeMs = 2_001L, maxPingAgeMs = 2_000L, vdSessionActive = true,
            ),
        )
        assertEquals(
            false,
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = false, pingAgeMs = Long.MAX_VALUE, maxPingAgeMs = 2_000L, vdSessionActive = false,
            ),
        )
    }

    @Test fun shizukuUnknownIdentityHasUnknownDispositionRegardlessOfStartRoute() {
        for (route in listOf(true, false, null)) {
            assertEquals(ShizukuHealDisposition.PROBE_UNKNOWN, ShizukuHealPlan.disposition(null, route))
        }
    }

    @Test fun shizukuPresentServerIsAliveButUnreachableRegardlessOfStartRoute() {
        val identity = ShizukuServerIdentity.Present(123, 456)
        for (route in listOf(true, false, null)) {
            assertEquals(ShizukuHealDisposition.ALIVE_BUT_UNREACHABLE, ShizukuHealPlan.disposition(identity, route))
        }
    }

    @Test fun shizukuAbsentServerWithUnknownRouteHasUnknownDisposition() {
        assertEquals(
            ShizukuHealDisposition.PROBE_UNKNOWN,
            ShizukuHealPlan.disposition(ShizukuServerIdentity.Absent, null),
        )
    }

    @Test fun shizukuAbsentServerWithPresentRouteCanStart() {
        assertEquals(
            ShizukuHealDisposition.START_SERVER,
            ShizukuHealPlan.disposition(ShizukuServerIdentity.Absent, true),
        )
    }

    @Test fun shizukuAbsentServerWithMissingRouteReportsMissingRoute() {
        assertEquals(
            ShizukuHealDisposition.START_ROUTE_MISSING,
            ShizukuHealPlan.disposition(ShizukuServerIdentity.Absent, false),
        )
    }

    @Test fun shizukuStartRouteParsing() {
        val cp = "/data/app/~~Hq6f==/moe.shizuku.privileged.api-y4p0==" // codePath shape incl. '~' and '='
        assertEquals(
            ShizukuRouteProbe.Available(ShizukuStartRoute.EXTRACTED_LIBS, cp),
            ShizukuHealPlan.parseStartRoute("EXTRACTED $cp\n"),
        )
        assertEquals(
            ShizukuRouteProbe.Available(ShizukuStartRoute.START_SCRIPT, cp),
            ShizukuHealPlan.parseStartRoute("SCRIPT $cp\n"),
        )
        assertEquals(
            ShizukuRouteProbe.Available(ShizukuStartRoute.START_SCRIPT, null),
            ShizukuHealPlan.parseStartRoute("SCRIPT \n"),
        )
        assertEquals(ShizukuRouteProbe.Missing, ShizukuHealPlan.parseStartRoute("MISSING\n"))
        for (invalid in listOf("", "garbage", "PRESENT", "EXTRACTED", "EXTRACTED /sdcard/x", "EXTRACTED $cp\nSCRIPT $cp")) {
            assertEquals(invalid, null, ShizukuHealPlan.parseStartRoute(invalid))
        }
    }

    @Test fun shizukuStartRouteProbePrefersExtractedLibrariesOverTheStarter() {
        val probe = ShizukuHealPlan.START_ROUTE_PROBE_COMMAND
        assertEquals(true, probe.contains(ShizukuHealPlan.RESOLVE_INSTALL_FRAGMENT))
        assertEquals(true, probe.indexOf("EXTRACTED") < probe.indexOf(SHIZUKU_START_SCRIPT_PATH))
        assertEquals(true, ShizukuHealPlan.RESOLVE_INSTALL_FRAGMENT.contains("libshizuku.so"))
        assertEquals(true, ShizukuHealPlan.RESOLVE_INSTALL_FRAGMENT.contains("dumpsys package $SHIZUKU_PACKAGE"))
    }

    @Test fun shizukuIdentityParsing() {
        assertEquals(ShizukuServerIdentity.Absent, ShizukuHealPlan.parseServerIdentity("ABSENT\n"))
        assertEquals(ShizukuServerIdentity.Present(123, 456), ShizukuHealPlan.parseServerIdentity("123 456\n"))
        for (invalid in listOf("UNKNOWN", "", "abc def", "0 5", "12", "1 2 3", "1 -1")) {
            assertEquals(invalid, null, ShizukuHealPlan.parseServerIdentity(invalid))
        }
    }

    @Test fun shizukuHealCommandRevalidatesIdentityOnDevice() {
        for (route in ShizukuStartRoute.values()) {
            val command = ShizukuHealPlan.buildHealCommand(route)
            assertEquals(true, command.contains(ShizukuHealPlan.IDENTITY_PROBE_COMMAND))
            assertEquals(true, command.contains("ABSENT) ;; *) echo ${ShizukuHealPlan.HEAL_ABORT_MARKER}; exit 0;;"))
            // Identity is re-checked before anything is started.
            assertEquals(true, command.indexOf(ShizukuHealPlan.HEAL_ABORT_MARKER) < command.indexOf("sleep 3"))
            assertEquals(false, command.contains("kill"))
            // Success is a live pid, failure is classified from the crash buffer, never from an exit code.
            assertEquals(true, command.contains("pidof shizuku_server"))
            assertEquals(true, command.contains("logcat -b crash -d -T"))
            // "&;" is a POSIX sh syntax error; background starts must be grouped.
            assertEquals(false, Regex("&\\s*;").containsMatchIn(command))
        }
        assertEquals(true, ShizukuHealPlan.IDENTITY_PROBE_COMMAND.contains("pidof shizuku_server"))
        assertEquals(true, ShizukuHealPlan.IDENTITY_PROBE_COMMAND.contains("/proc/"))
    }

    @Test fun healStartsOnlyUnderTheSharedStartLockAndRechecksInside() {
        for (route in ShizukuStartRoute.values()) {
            val command = ShizukuHealPlan.buildHealCommand(route)
            assertEquals(true, command.startsWith("{ ${ShizukuHealPlan.ACQUIRE_START_LOCK}; [ \"\$lk\" = 1 ] || { echo ${ShizukuHealPlan.HEAL_ABORT_MARKER}; exit 0; };"))
            assertEquals(true, command.endsWith("} 8>>${ShizukuHealPlan.START_LOCK_FILE}"))
            // The identity re-check happens after the lock is taken.
            assertEquals(true, command.indexOf("flock -n 8") < command.indexOf(ShizukuHealPlan.IDENTITY_PROBE_COMMAND))
            assertEquals(true, command.contains("8>&- 9>&-"))
        }
    }

    @Test fun extractedRouteStartsADetachedServerFromTheExtractedLibraries() {
        val command = ShizukuHealPlan.buildHealCommand(ShizukuStartRoute.EXTRACTED_LIBS)
        assertEquals(true, command.contains("{ setsid /system/bin/app_process"))
        assertEquals(true, command.contains("-Djava.class.path=\"\$cp/base.apk\""))
        assertEquals(true, command.contains("-Dshizuku.library.path=\"\$lib\""))
        assertEquals(true, command.contains("--nice-name=shizuku_server rikka.shizuku.server.ShizukuService"))
        assertEquals(false, command.contains("base.apk!/"))
        assertEquals(false, command.contains(SHIZUKU_START_SCRIPT_PATH))
    }

    @Test fun scriptRouteScansStarterOutputInsteadOfTrustingItsExitCode() {
        val command = ShizukuHealPlan.buildHealCommand(ShizukuStartRoute.START_SCRIPT)
        assertEquals(true, command.contains("sh $SHIZUKU_START_SCRIPT_PATH"))
        assertEquals(true, command.contains("grep -q '^fatal:' && echo ${ShizukuHealPlan.STARTER_FATAL_MARKER}"))
        assertEquals(false, command.contains("app_process"))
    }

    @Test fun startOutcomesAreParsedFromMarkers() {
        val cp = "/data/app/~~a==/moe.shizuku.privileged.api-b=="
        assertEquals(ShizukuStartOutcome.Started(4321), ShizukuHealPlan.parseStartOutcome("HEAL_STARTED 4321\n"))
        assertEquals(ShizukuStartOutcome.Started(4321), ShizukuHealPlan.parseStartOutcome("HEAL_STARTED 4321 4322\n"))
        assertEquals(ShizukuStartOutcome.Unparsed, ShizukuHealPlan.parseStartOutcome("HEAL_STARTED x\n"))
        assertEquals(ShizukuStartOutcome.IdentityAborted, ShizukuHealPlan.parseStartOutcome("HEAL_ABORT_IDENTITY\n"))
        assertEquals(ShizukuStartOutcome.RouteGone, ShizukuHealPlan.parseStartOutcome("HEAL_ROUTE_GONE\n"))
        assertEquals(ShizukuStartOutcome.TerminalLinkError(cp), ShizukuHealPlan.parseStartOutcome("HEAL_TERMINAL_LINK $cp\n"))
        assertEquals(ShizukuStartOutcome.TerminalLinkError(null), ShizukuHealPlan.parseStartOutcome("HEAL_TERMINAL_LINK \n"))
        assertEquals(ShizukuStartOutcome.Died(false), ShizukuHealPlan.parseStartOutcome("HEAL_DIED\n"))
        assertEquals(
            ShizukuStartOutcome.Died(true),
            ShizukuHealPlan.parseStartOutcome("HEAL_STARTER_FATAL\nHEAL_DIED\n"),
        )
        assertEquals(ShizukuStartOutcome.Unparsed, ShizukuHealPlan.parseStartOutcome(""))
        assertEquals(ShizukuStartOutcome.Unparsed, ShizukuHealPlan.parseStartOutcome("info: start.sh begin\n"))
    }

    @Test fun terminalSignatureMatchesOnlyShizukuLibraries() {
        val signature = Regex(ShizukuHealPlan.TERMINAL_LINK_SIGNATURE)
        val shizuku = "java.lang.UnsatisfiedLinkError: dlopen failed: library " +
            "\"/data/app/~~a==/moe.shizuku.privileged.api-b==/base.apk!/lib/arm64-v8a/librish.so\" not found"
        assertEquals(true, signature.containsMatchIn(shizuku))
        assertEquals(true, signature.containsMatchIn("java.lang.UnsatisfiedLinkError: couldn't find libshizuku.so"))
        assertEquals(false, signature.containsMatchIn("java.lang.UnsatisfiedLinkError: dlopen failed: libfoo.so not found"))
        assertEquals(false, signature.containsMatchIn("moe.shizuku.privileged.api crashed: NullPointerException"))
        val command = ShizukuHealPlan.buildHealCommand(ShizukuStartRoute.EXTRACTED_LIBS)
        assertEquals(true, command.contains("grep -qE '${ShizukuHealPlan.TERMINAL_LINK_SIGNATURE}'"))
    }

    @Test fun terminalStateIsReprobedOnItsOwnScheduleWithoutUsingStartAttempts() {
        val interval = ShizukuHealPlan.TERMINAL_RECHECK_INTERVAL_MS
        assertEquals(true, ShizukuHealPlan.terminalRecheckDue(false, 10, 9))
        assertEquals(true, ShizukuHealPlan.terminalRecheckDue(true, 10, null))
        assertEquals(false, ShizukuHealPlan.terminalRecheckDue(true, interval - 1, 0))
        assertEquals(true, ShizukuHealPlan.terminalRecheckDue(true, interval, 0))
    }

    @Test fun selfRestartLatchRefusesStartsOnlyWhileEngaged() {
        SelfRestartLatch.release()
        assertEquals(false, SelfRestartLatch.isEngaged(1_000))
        SelfRestartLatch.engage(1_000, 30_000)
        assertEquals(true, SelfRestartLatch.isEngaged(30_999))
        assertEquals(false, SelfRestartLatch.isEngaged(31_000))
        SelfRestartLatch.engage(1_000, 30_000)
        SelfRestartLatch.release()
        assertEquals(false, SelfRestartLatch.isEngaged(1_001))
    }

    @Test fun terminalFailureBlocksOnlyTheSameInstall() {
        val cp = "/data/app/~~a==/moe.shizuku.privileged.api-b=="
        assertEquals(true, ShizukuHealPlan.startBlockedByTerminal(cp, cp))
        assertEquals(false, ShizukuHealPlan.startBlockedByTerminal(cp, "/data/app/~~c==/moe.shizuku.privileged.api-d=="))
        assertEquals(false, ShizukuHealPlan.startBlockedByTerminal(null, cp))
        assertEquals(false, ShizukuHealPlan.startBlockedByTerminal(cp, null))
    }

    @Test fun selfRestartRequiresAnIdleDeviceAndRespectsItsBudget() {
        val now = 10 * SelfRestartPlan.DAY_MS
        assertEquals(SelfRestartDecision.DISPATCH, SelfRestartPlan.decide(0, false, true, now, emptyList()))
        assertEquals(SelfRestartDecision.SKIP_ACTIVE_RUNS, SelfRestartPlan.decide(1, false, true, now, emptyList()))
        assertEquals(SelfRestartDecision.SKIP_VD_BUSY, SelfRestartPlan.decide(0, true, true, now, emptyList()))
        assertEquals(SelfRestartDecision.SKIP_ADB, SelfRestartPlan.decide(0, false, false, now, emptyList()))
        val recent = now - SelfRestartPlan.MIN_INTERVAL_MS + 1
        assertEquals(SelfRestartDecision.SKIP_INTERVAL, SelfRestartPlan.decide(0, false, true, now, listOf(recent)))
        val spaced = listOf(now - 3 * 60 * 60_000L, now - 2 * 60 * 60_000L)
        assertEquals(SelfRestartDecision.SKIP_BUDGET, SelfRestartPlan.decide(0, false, true, now, spaced))
        val old = listOf(now - SelfRestartPlan.DAY_MS, now - SelfRestartPlan.DAY_MS - 1)
        assertEquals(SelfRestartDecision.DISPATCH, SelfRestartPlan.decide(0, false, true, now, old))
        // A clock that moved backwards makes the gate stricter, never looser.
        assertEquals(SelfRestartDecision.SKIP_INTERVAL, SelfRestartPlan.decide(0, false, true, now, listOf(now + 5_000)))
    }

    @Test fun selfRestartHistoryRoundTripsAndStaysBounded() {
        val now = 10 * SelfRestartPlan.DAY_MS
        assertEquals(listOf(1L, 2L), SelfRestartPlan.parseHistory("1, 2,x,"))
        assertEquals(emptyList<Long>(), SelfRestartPlan.parseHistory(null))
        val stored = SelfRestartPlan.appendHistory(listOf(now - SelfRestartPlan.DAY_MS - 1, now - 10, now - 5, now - 1), now)
        assertEquals(listOf(now - 10, now - 5, now - 1, now), SelfRestartPlan.parseHistory(stored))
    }

    @Test fun selfRestartCommandIsDetachedAndPreservesOtherServices() {
        val command = SelfRestartPlan.buildCommand(
            "ai.closepaw",
            AccessibilityServiceSetting.AGENT,
            aliases = listOf(AccessibilityServiceSetting.AGENT_SHORT),
        )
        // Survives ClosePaw's early stream close: double fork, SIGHUP ignored, reply after a wait.
        assertEquals(true, command.startsWith("( nohup setsid sh -c '"))
        assertEquals(true, command.endsWith("& ) ; sleep 1; echo ${SelfRestartPlan.DISPATCHED_MARKER}"))
        assertEquals(true, command.contains("sh -c 'sleep 2; am force-stop"))
        assertEquals(true, command.contains("am force-stop ai.closepaw"))
        assertEquals(true, command.indexOf("am force-stop") < command.indexOf("settings put secure accessibility_enabled 1"))
        assertEquals(false, command.contains("am start"))
        assertEquals(false, command.contains("monkey"))
        assertEquals(false, command.contains("kill"))
        assertThrows(IllegalArgumentException::class.java) {
            SelfRestartPlan.buildCommand("ai.closepaw; reboot", AccessibilityServiceSetting.AGENT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelfRestartPlan.buildCommand("ai.closepaw", "ai.closepaw/x'; reboot")
        }
    }

    @Test fun shizukuHealRespectsAttemptLimits() {
        assertEquals(ShizukuHealPlan.NO_OP, ShizukuHealPlan.decide(false, false, true, 1, 299_000))
        assertEquals(ShizukuHealPlan.NO_OP, ShizukuHealPlan.decide(false, false, true, 4, 300_000))
        assertEquals(ShizukuHealPlan.START, ShizukuHealPlan.decide(false, false, true, 0, null))
        assertEquals(ShizukuHealPlan.START, ShizukuHealPlan.decide(false, false, true, 3, 300_000))
    }

    @Test fun shizukuAttemptsAtTheHourBoundaryExpire() {
        assertEquals(1, attemptsInLastHour(listOf(0L, 1L, 2L), nowMs = 3_600_001L))
    }

    @Test fun externalSelfRestartUsesTheH3GatesAndSharedHistory() {
        val now = 10 * SelfRestartPlan.DAY_MS
        fun d(alive: Boolean = false, heal: Boolean = false, runs: Int = 0, vd: Boolean = false, h: List<Long> = emptyList()) =
            ExternalSelfRestartPlan.decide(alive, heal, runs, vd, now, h)
        assertEquals(ExternalSelfRestartPlan.GO, d())
        assertEquals(ExternalSelfRestartPlan.NOT_NEEDED, d(alive = true, runs = 3))
        assertEquals(ExternalSelfRestartPlan.HEAL_IN_FLIGHT, d(heal = true))
        assertEquals(SelfRestartDecision.SKIP_ACTIVE_RUNS.code, d(runs = 1))
        assertEquals(SelfRestartDecision.SKIP_VD_BUSY.code, d(vd = true))
        // An H-3 dispatch a minute ago (shared history) blocks the watchdog, and vice versa.
        assertEquals(SelfRestartDecision.SKIP_INTERVAL.code, d(h = listOf(now - 60_000)))
        assertEquals(SelfRestartDecision.SKIP_BUDGET.code, d(h = listOf(now - 7_200_000, now - 3_600_000)))
    }

    @Test fun watchdogRestartsOnlyAVanishedServerAndAsksBeforeRestartingClosePaw() {
        val restart = SelfRestartPlan.restartScript(
            "ai.closepaw", AccessibilityServiceSetting.AGENT, listOf(AccessibilityServiceSetting.AGENT_SHORT),
        )
        val script = HealWatchdogPlan.script(restart, "ai.closepaw.mcpcredential")
        assertEquals(false, script.contains("§"))
        assertEquals(false, script.contains("kill"))
        assertEquals(false, Regex("&\\s*;").containsMatchIn(script))
        assertEquals(true, script.contains("echo \$\$ > \"\$PIDF\""))
        // Single instance: the whole body runs under a non-blocking flock held for life.
        assertEquals(true, script.startsWith("{ flock -n 9 || exit 0;"))
        assertEquals(true, script.endsWith("} 9>>${HealWatchdogPlan.INSTANCE_LOCK_FILE}"))
        // Server start shares ClosePaw's H-1 start lock; the started server holds neither lock.
        assertEquals(true, script.contains("{ ${ShizukuHealPlan.ACQUIRE_START_LOCK}; if [ \"\$lk\" = 1 ]; then"))
        assertEquals(true, script.contains("} 8>>${ShizukuHealPlan.START_LOCK_FILE};"))
        assertEquals(true, script.contains("8>&- 9>&- & }"))
        assertEquals(true, script.contains("if pidof shizuku_server >/dev/null 2>&1; then absent=0;"))
        assertEquals(true, script.contains("[ \"\$absent\" -ge ${HealWatchdogPlan.ABSENT_GRACE_S} ]"))
        assertEquals(true, script.contains(ShizukuHealPlan.RESOLVE_INSTALL_FRAGMENT))
        // Re-checks just before starting so a server ClosePaw started meanwhile is left alone.
        assertEquals(true, script.contains("if [ -n \"\$lib\" ] && ! pidof shizuku_server >/dev/null 2>&1; then"))
        assertEquals(true, script.contains("{ setsid /system/bin/app_process"))
        assertEquals(true, script.contains("content call --uri content://ai.closepaw.mcpcredential --method selfRestart"))
        // The restart sequence runs only in the decision=go branch.
        val goBranch = script.substringAfter("*decision=go*)").substringBefore(";;")
        assertEquals(true, goBranch.contains("am force-stop ai.closepaw"))
        assertEquals(1, Regex("am force-stop").findAll(script).count())
        assertThrows(IllegalArgumentException::class.java) { HealWatchdogPlan.script(restart, "x; reboot") }
    }

    @Test fun watchdogLaunchIsSingleInstanceAndDetached() {
        val cmd = HealWatchdogPlan.launchCommand("sleep 1")
        assertEquals(true, cmd.contains("grep -q ${HealWatchdogPlan.NAME} /proc/\$p/cmdline"))
        assertEquals(true, cmd.contains("then echo ${HealWatchdogPlan.RUNNING_MARKER};"))
        assertEquals(true, cmd.contains("( nohup setsid sh -c 'sleep 1' ${HealWatchdogPlan.NAME} </dev/null >/dev/null 2>&1 & ) ; sleep 1; echo ${HealWatchdogPlan.STARTED_MARKER}"))
    }

    @Test fun selfRestartCommandStillWrapsTheSharedRestartScript() {
        val script = SelfRestartPlan.restartScript("ai.closepaw", AccessibilityServiceSetting.AGENT)
        val cmd = SelfRestartPlan.buildCommand("ai.closepaw", AccessibilityServiceSetting.AGENT)
        assertEquals(true, cmd.contains(SelfRestartPlan.shellQuote(script)))
    }
}
