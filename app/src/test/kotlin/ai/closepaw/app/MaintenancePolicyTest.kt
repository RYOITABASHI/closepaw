package ai.closepaw.app

import org.junit.Assert.assertEquals
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

    @Test fun shizukuHealActionMatchesTheEffectTimeIdentity() {
        val diagnosed = ShizukuServerIdentity.Present(123, 456)
        val absent = ShizukuServerIdentity.Absent
        val cases = listOf(
            Triple(absent, absent, ShizukuHealAction.RUN_STARTER),
            Triple(absent, diagnosed, ShizukuHealAction.ABORT),
            Triple(diagnosed, absent, ShizukuHealAction.RUN_STARTER),
            Triple(diagnosed, diagnosed, ShizukuHealAction.KILL_THEN_STARTER),
            Triple(diagnosed, ShizukuServerIdentity.Present(124, 456), ShizukuHealAction.ABORT),
            Triple(diagnosed, ShizukuServerIdentity.Present(123, 457), ShizukuHealAction.ABORT),
            Triple(absent, null, ShizukuHealAction.ABORT),
            Triple(diagnosed, null, ShizukuHealAction.ABORT),
        )
        for ((previous, current, expected) in cases) {
            assertEquals("$previous -> $current", expected, ShizukuHealPlan.healAction(previous, current))
        }
    }

    @Test fun shizukuPostKillActionRequiresAbsence() {
        val cases = listOf(
            ShizukuServerIdentity.Absent to ShizukuHealAction.RUN_STARTER,
            ShizukuServerIdentity.Present(123, 456) to ShizukuHealAction.ABORT,
            ShizukuServerIdentity.Present(124, 456) to ShizukuHealAction.ABORT,
            null to ShizukuHealAction.ABORT,
        )
        for ((observed, expected) in cases) {
            assertEquals("$observed", expected, ShizukuHealPlan.postKillAction(observed))
        }
    }

    @Test fun shizukuIdentityParsing() {
        assertEquals(ShizukuServerIdentity.Absent, ShizukuHealPlan.parseServerIdentity("ABSENT\n"))
        assertEquals(ShizukuServerIdentity.Present(123, 456), ShizukuHealPlan.parseServerIdentity("123 456\n"))
        for (invalid in listOf("UNKNOWN", "", "abc def", "0 5", "12", "1 2 3", "1 -1")) {
            assertEquals(invalid, null, ShizukuHealPlan.parseServerIdentity(invalid))
        }
    }

    @Test fun shizukuHealCommandRevalidatesIdentityOnDevice() {
        val starter = "RUN_STARTER_SENTINEL"
        val present = ShizukuHealPlan.buildHealCommand(ShizukuServerIdentity.Present(123, 456), starter)
        val absent = ShizukuHealPlan.buildHealCommand(ShizukuServerIdentity.Absent, starter)
        assertEquals(true, present.contains("'123'"))
        assertEquals(true, present.contains("'456'"))
        assertEquals(false, absent.contains("123"))
        assertEquals(1, Regex("kill -9 123").findAll(present).count())
        assertEquals(true, present.contains("-lt 20"))
        assertEquals(false, absent.contains("kill"))
        for (forbidden in listOf("pkill", "killall", "kill \$(")) {
            assertEquals(forbidden, false, present.contains(forbidden))
        }
        for (command in listOf(present, absent)) {
            assertEquals(true, command.contains(ShizukuHealPlan.HEAL_ABORT_MARKER))
            assertEquals(1, Regex(starter).findAll(command).count())
        }
        assertEquals(true, ShizukuHealPlan.IDENTITY_PROBE_COMMAND.contains("pidof shizuku_server"))
        assertEquals(true, ShizukuHealPlan.IDENTITY_PROBE_COMMAND.contains("/proc/"))
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
}
