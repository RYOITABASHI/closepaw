package ai.closepaw.app

import ai.closepaw.adb.AdbShellChannel
import ai.closepaw.platform.virtualdisplay.ShizukuClient
import ai.closepaw.platform.virtualdisplay.ShizukuHealGate
import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import android.os.PowerManager
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Background work scoped to the enabled MCP controller. */
internal class McpMaintenance(
    private val app: Context,
    private val channel: AdbShellChannel,
    private val runStore: RunRecoveryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastRecovery: Long? = null
    private var lastScan: Long? = null
    private var activeScan: CompletableDeferred<Unit>? = null

    private val shizuku = ShizukuClient()
    private data class ShizukuPingSample(val alive: Boolean, val atMs: Long)

    @Volatile private var lastShizukuPing: ShizukuPingSample? = null
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        lastShizukuPing = ShizukuPingSample(false, SystemClock.elapsedRealtime())
        checkRecovery()
    }
    private val recoveryCheck = Mutex()
    private val shizukuAttempts = ArrayDeque<Long>()

    private data class Check(val atMs: Long, val accessibility: String, val adb: String, val shizuku: String)
    private data class Heal(val atMs: Long, val detail: String)

    @Volatile private var lastCheck: Check? = null
    @Volatile private var lastHeal: Heal? = null

    fun status() = buildJsonObject {
        val check = lastCheck
        val heal = lastHeal
        val writes = runStore.writeStatus
        put("runStoreWritesFailing", writes.failing)
        put("runStoreLastWriteFailureAtMs", writes.lastFailureAtMs?.let(::JsonPrimitive) ?: JsonNull)
        put("resident", McpRecoveryService.resident)
        put("residentStartFailure", McpRecoveryService.startFailure?.let(::JsonPrimitive) ?: JsonNull)
        put("lastCheckAtMs", check?.atMs?.let(::JsonPrimitive) ?: JsonNull)
        put("accessibility", check?.accessibility ?: "pending")
        put("adb", check?.adb ?: "pending")
        put("shizuku", check?.shizuku ?: "pending")
        put("lastHealAtMs", heal?.atMs?.let(::JsonPrimitive) ?: JsonNull)
        put("lastHeal", heal?.detail?.let(::JsonPrimitive) ?: JsonNull)
        put("writeSecureSettingsGranted", app.canWriteSecureSettings())
        put("readLogsGranted", app.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED)
        val batteryExempt = try {
            app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization exemption query failed: ${e.javaClass.simpleName}")
            null
        }
        put("batteryOptimizationExempt", batteryExempt?.let(::JsonPrimitive) ?: JsonNull)
        put("shizukuPermissionGranted", shizuku.permissionGrantedOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        put("shizukuAlive", shizuku.isAvailable())
        put("accessibilityBound", AgentService.instance != null)
        put("adbConnected", channel.isConnected())
    }

    private fun recordHeal(detail: String) {
        lastHeal = Heal(System.currentTimeMillis(), detail)
        Log.i(TAG, detail)
    }

    fun stop() {
        try {
            shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        } finally {
            scope.cancel()
        }
    }

    fun start() {
        shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        checkRecovery()
    }

    fun checkRecovery() {
        scope.launch {
            if (!recoveryCheck.tryLock()) return@launch
            try {
                val accessibility = checkStep("accessibility") {
                    checkAccessibility()
                    if (AgentService.instance != null && accessibilitySettingOn(app)) "ready" else "unavailable"
                }
                val adb = checkStep("adb") {
                    when {
                        channel.isConnected() -> "connected"
                        !channel.paired -> "unpaired"
                        else -> {
                            ensureRecentScan()
                            if (channel.connectKnownPorts()) {
                                recordHeal("adb_reconnected")
                                "connected"
                            } else "unavailable"
                        }
                    }
                }
                val shizukuState = checkStep("shizuku") {
                    healShizuku()
                    when {
                        !pingShizukuBinder() -> "unavailable"
                        !shizuku.hasPermission() -> "permission_missing"
                        else -> "ready"
                    }
                }
                lastCheck = Check(System.currentTimeMillis(), accessibility, adb, shizukuState)
                Log.i(TAG, "Recovery check accessibility=$accessibility adb=$adb shizuku=$shizukuState")
            } finally {
                recoveryCheck.unlock()
            }
        }
    }

    private suspend fun checkStep(name: String, block: suspend () -> String): String = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Recovery check failed: $name", e)
        "error:${e.javaClass.simpleName}"
    }

    private suspend fun healShizuku() {
        if (!ShizukuHealGate.tryClaimHeal()) return
        try {
            healShizukuClaimed()
        } finally {
            ShizukuHealGate.releaseHeal()
        }
    }

    private suspend fun healShizukuClaimed() {
        if (awaitShizukuBinder(CONFIRM_DOWN_MS, pollMs = 100L)) return
        val now = SystemClock.elapsedRealtime()
        val plan = ShizukuHealPlan.decide(
            binderAlive = false,
            vdSessionActive = vdBusy(),
            channelPaired = channel.paired,
            attemptsInLastHour = attemptsInLastHour(shizukuAttempts, now),
            msSinceLastAttempt = shizukuAttempts.lastOrNull()?.let { now - it },
        )
        if (plan == ShizukuHealPlan.NO_OP) return

        if (!channel.isConnected()) {
            if (channel.paired) ensureRecentScan()
            if (!channel.paired || !channel.connectKnownPorts()) {
                recordHeal("shizuku_adb_unavailable")
                return
            }
        }

        if (vdBusy() || pingShizukuBinder()) return
        // The check mutex guards the history. Only the most recent four claims can affect the gate.
        if (shizukuAttempts.size == ShizukuHealPlan.MAX_ATTEMPTS_PER_HOUR) shizukuAttempts.removeFirst()
        shizukuAttempts.addLast(SystemClock.elapsedRealtime())
        recordHeal("shizuku_attempt")
        val disposition = try {
            val probe = channel.exec(ShizukuHealPlan.IDENTITY_PROBE_COMMAND, IDENTITY_TIMEOUT_MS)
            val identity = if (probe.exitCode == 0 && !probe.truncated) {
                ShizukuHealPlan.parseServerIdentity(probe.stdout)
            } else null
            val startRoutePresent = if (identity == ShizukuServerIdentity.Absent) {
                val routeProbe = channel.exec(ShizukuHealPlan.START_ROUTE_PROBE_COMMAND, IDENTITY_TIMEOUT_MS)
                if (routeProbe.exitCode == 0 && !routeProbe.truncated) {
                    ShizukuHealPlan.parseStartRoutePresent(routeProbe.stdout)
                } else null
            } else null
            ShizukuHealPlan.disposition(identity, startRoutePresent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ShizukuHealDisposition.PROBE_UNKNOWN
        }
        when (disposition) {
            ShizukuHealDisposition.START_SERVER -> Log.i(TAG, "Shizuku heal disposition: start_server")
            ShizukuHealDisposition.ALIVE_BUT_UNREACHABLE -> {
                recordHeal("shizuku_alive_but_unreachable")
                return
            }
            ShizukuHealDisposition.START_ROUTE_MISSING -> {
                recordHeal("shizuku_start_route_missing")
                return
            }
            ShizukuHealDisposition.PROBE_UNKNOWN -> {
                recordHeal("shizuku_probe_unknown")
                return
            }
        }
        val result = channel.execGuarded(
            ShizukuHealPlan.buildHealCommand(SHIZUKU_START_COMMAND),
            START_TIMEOUT_MS,
        ) {
            val sample = lastShizukuPing
            ShizukuHealPlan.shouldDispatch(
                cachedBinderAlive = sample?.alive ?: true,
                pingAgeMs = sample?.let { SystemClock.elapsedRealtime() - it.atMs } ?: Long.MAX_VALUE,
                maxPingAgeMs = ShizukuHealPlan.MAX_PING_AGE_MS,
                vdSessionActive = vdBusy(),
            )
        }
        if (result == null) {
            recordHeal("shizuku_dispatch_aborted")
            return
        }
        if (result.stdout.contains(ShizukuHealPlan.HEAL_ABORT_MARKER)) {
            recordHeal("shizuku_probe_aborted")
            return
        }
        if (result.exitCode != 0) {
            recordHeal("shizuku_start_failed_${result.exitCode}")
            return
        }
        Log.i(TAG, "Shizuku start.sh exited successfully")

        if (!awaitShizukuBinder(STARTUP_POLL_TIMEOUT_MS, pollMs = 250L)) {
            recordHeal("shizuku_start_timeout")
            return
        }
        if (!shizuku.hasPermission()) {
            recordHeal("shizuku_permission_missing")
            return
        }
        recordHeal("shizuku_recovered")
        shizukuAttempts.clear()
    }

    private fun vdBusy(): Boolean = isVirtualDisplaySessionActive() || ShizukuHealGate.vdActivationInFlight

    private fun isVirtualDisplaySessionActive(): Boolean =
        (AgentService.instance?.isVirtualDisplaySessionActive() ?: false) ||
            VirtualDisplayPlatform.anySessionActive()

    private fun pingShizukuBinder(): Boolean {
        val alive = shizuku.isAvailable()
        lastShizukuPing = ShizukuPingSample(alive, SystemClock.elapsedRealtime())
        return alive
    }

    private suspend fun awaitShizukuBinder(timeoutMs: Long, pollMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!pingShizukuBinder()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return false
            delay(minOf(remaining, pollMs))
        }
        return true
    }

    private suspend fun checkAccessibility() {
        if (accessibilitySettingOn(app) && AgentService.instance != null) return
        if (!claimRecovery()) return
        val direct = app.canWriteSecureSettings()
        if (!direct && !channel.isConnected()) {
            if (channel.paired) ensureRecentScan()
            if (!channel.paired || !channel.connectKnownPorts()) {
                recordHeal("accessibility_skipped_no_permission_or_adb")
                return
            }
        }
        val route = if (direct) "content_resolver" else "adb"
        recordHeal("accessibility_attempt_$route")
        try {
            recoverAccessibility(direct)
            recordHeal(
                if (AgentService.instance != null && accessibilitySettingOn(app)) "accessibility_recovered_$route"
                else "accessibility_unbound_$route",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordHeal("accessibility_failed_$route")
            throw e
        }
    }

    private suspend fun recoverAccessibility(direct: Boolean) {
        val current = readServices(direct)
        when (AccessibilityRecoveryPlan.decide(
            AccessibilityServiceSetting.containsAgent(current),
            AgentService.instance != null,
        )) {
            AccessibilityRecoveryPlan.NO_OP -> return
            AccessibilityRecoveryPlan.APPEND -> if (!updateServices(direct, agentExpected = true)) return
            AccessibilityRecoveryPlan.TOGGLE_REBIND -> {
                // Finish the bounded re-add even if the listener stops after the removal write.
                val toggled = withContext(NonCancellable) {
                    val removed = updateServices(direct, agentExpected = false)
                    // Give the settings observer time to reconcile the disabled component.
                    if (removed) delay(500)
                    val restored = updateServices(direct, agentExpected = true)
                    if (!restored) {
                        val present = try {
                            AccessibilityServiceSetting.containsAgent(readServices(direct))
                        } catch (_: Exception) {
                            null
                        }
                        val state = when (present) {
                            false -> "component is currently REMOVED"
                            true -> "component is present but re-add was not verified"
                            null -> "component may still be REMOVED (setting unreadable)"
                        }
                        Log.e(TAG, "Accessibility recovery aborted: $state")
                    }
                    removed && restored
                }
                if (!toggled) return
            }
        }
        val enabled = if (direct) {
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        } else {
            channel.exec("settings put secure accessibility_enabled 1", SETTINGS_TIMEOUT_MS).exitCode == 0
        }
        if (!enabled) throw IOException("Cannot enable accessibility")
        for (poll in 1..20) {
            if (AgentService.instance != null) break
            delay(500)
        }
        if (AgentService.instance != null) {
            Log.i(TAG, "Accessibility recovered/bound")
        } else {
            Log.w(TAG, "Accessibility settings written but still unbound")
        }
    }

    private suspend fun readServices(direct: Boolean): String? {
        if (direct) {
            return Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }
        val result = channel.exec(READ_SERVICES, SETTINGS_TIMEOUT_MS)
        if (result.exitCode != 0 || result.truncated || !result.stdout.endsWith('\n')) {
            throw IOException("Cannot read enabled services")
        }
        return result.stdout.removeSuffix("\n")
    }

    private suspend fun updateServices(direct: Boolean, agentExpected: Boolean): Boolean {
        val operation = if (agentExpected) "add" else "remove"
        repeat(3) { attempt ->
            try {
                val current = readServices(direct)
                val before = AccessibilityServiceSetting.entries(current)
                val merged = if (agentExpected) AccessibilityServiceSetting.withAgent(current)
                    else AccessibilityServiceSetting.withoutAgent(current.orEmpty())
                if (!AccessibilityServiceSetting.verifyUpdate(before, merged, merged, agentExpected)) {
                    Log.e(TAG, "Enabled-services $operation aborted: cannot preserve existing entries")
                    return false
                }
                if (writeServices(direct, current, merged) && AccessibilityServiceSetting.verifyUpdate(
                    before,
                    merged,
                    readServices(direct),
                    agentExpected,
                )) return true
                Log.w(TAG, "Enabled-services $operation attempt ${attempt + 1} failed guard or read-back")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Enabled-services $operation attempt ${attempt + 1} failed: ${e.javaClass.simpleName}")
            }
            if (attempt < 2) delay(200)
        }
        Log.e(TAG, "Enabled-services $operation aborted after three attempts")
        return false
    }

    private suspend fun writeServices(direct: Boolean, expected: String?, value: String): Boolean {
        // Android Settings has no compare-and-set. Re-read immediately before the put.
        if (direct) {
            return readServices(direct = true) == expected &&
                Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        }
        // Preserve trailing newlines in the shell's comparison by adding a sentinel.
        val command = "current=\$($READ_SERVICES && printf .) || exit 1; " +
            "[ \"\$current\" = ${shellQuote(checkNotNull(expected) + "\n.")} ] || exit 2; " +
            "settings put secure enabled_accessibility_services ${shellQuote(value)}"
        return channel.exec(command, SETTINGS_TIMEOUT_MS).exitCode == 0
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun ensureRecentScan() {
        val (scan, claimed) = synchronized(this) {
            when (MaintenanceScanGate.decide(SystemClock.elapsedRealtime(), lastScan, activeScan != null)) {
                MaintenanceScanGate.SKIP -> return
                MaintenanceScanGate.JOIN -> checkNotNull(activeScan) to false
                MaintenanceScanGate.CLAIM -> CompletableDeferred<Unit>().also { activeScan = it } to true
            }
        }
        if (!claimed) {
            scan.await()
            return
        }
        try {
            if (!channel.rediscover()) Log.w(TAG, "ADB rediscovery found no usable port or promotion failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ADB rediscovery failed: ${e.javaClass.simpleName}")
        } finally {
            synchronized(this) {
                lastScan = SystemClock.elapsedRealtime()
                activeScan = null
                scan.complete(Unit)
            }
        }
    }

    @Synchronized
    private fun claimRecovery(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastRecovery?.let { now - it < RECOVERY_INTERVAL_MS } == true) return false
        lastRecovery = now
        return true
    }

    companion object {
        private const val TAG = "ClosePawMcpAudit"
        private const val READ_SERVICES = "settings get secure enabled_accessibility_services"
        private const val SETTINGS_TIMEOUT_MS = 5_000L
        private const val RECOVERY_INTERVAL_MS = 5 * 60_000L
        private const val CONFIRM_DOWN_MS = 2_000L
        private const val IDENTITY_TIMEOUT_MS = 5_000L
        private const val START_TIMEOUT_MS = 15_000L
        private const val STARTUP_POLL_TIMEOUT_MS = 10_000L
        private const val SHIZUKU_START_COMMAND = "sh $SHIZUKU_START_SCRIPT_PATH"
    }
}

internal fun accessibilitySettingOn(app: Context): Boolean =
    AccessibilityServiceSetting.containsAgent(
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    )
