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
        assertEquals(true, ShizukuHealPlan.parseStartRoutePresent("PRESENT\n"))
        assertEquals(false, ShizukuHealPlan.parseStartRoutePresent("MISSING\n"))
        assertEquals(null, ShizukuHealPlan.parseStartRoutePresent("garbage"))
        assertEquals(null, ShizukuHealPlan.parseStartRoutePresent(""))
    }

    @Test fun shizukuStartRouteProbeReferencesTheStartScript() {
        assertEquals(true, ShizukuHealPlan.START_ROUTE_PROBE_COMMAND.contains(SHIZUKU_START_SCRIPT_PATH))
    }

    @Test fun shizukuIdentityParsing() {
        assertEquals(ShizukuServerIdentity.Absent, ShizukuHealPlan.parseServerIdentity("ABSENT\n"))
        assertEquals(ShizukuServerIdentity.Present(123, 456), ShizukuHealPlan.parseServerIdentity("123 456\n"))
        for (invalid in listOf("UNKNOWN", "", "abc def", "0 5", "12", "1 2 3", "1 -1")) {
            assertEquals(invalid, null, ShizukuHealPlan.parseServerIdentity(invalid))
        }
    }

    @Test fun shizukuHealCommandRevalidatesIdentityOnDevice() {
        val starter = "sh $SHIZUKU_START_SCRIPT_PATH"
        val command = ShizukuHealPlan.buildHealCommand(starter)
        assertEquals(true, command.contains(ShizukuHealPlan.IDENTITY_PROBE_COMMAND))
        assertEquals(true, command.contains("ABSENT) $starter"))
        assertEquals(true, command.contains(ShizukuHealPlan.HEAL_ABORT_MARKER))
        assertEquals(1, Regex(Regex.escape(starter)).findAll(command).count())
        assertEquals(false, command.contains("kill"))
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
