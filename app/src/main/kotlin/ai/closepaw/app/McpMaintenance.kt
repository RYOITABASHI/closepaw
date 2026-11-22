package ai.closepaw.app

import ai.closepaw.adb.AdbShellChannel
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
import kotlinx.coroutines.withContext

/** Background work scoped to one MCP listener. */
internal class McpMaintenance(private val app: Context, private val channel: AdbShellChannel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastRecovery: Long? = null
    private var lastScan: Long? = null
    private var activeScan: CompletableDeferred<Unit>? = null

    fun stop() = scope.cancel()

    fun onServerStarted() {
        checkAccessibility()
        rediscover()
    }

    fun checkAccessibility() {
        scope.launch {
            try {
                if (!channel.paired) return@launch
                if (accessibilitySettingOn(app) && AgentService.instance != null) return@launch
                if (!claimRecovery()) return@launch
                if (!channel.isConnected()) {
                    ensureRecentScan()
                    check(channel.connectKnownPorts()) { "ADB connection unavailable" }
                }
                recoverAccessibility()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility recovery failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun recoverAccessibility() {
        val current = readServices().removeSuffix("\n")
        when (AccessibilityRecoveryPlan.decide(
            AccessibilityServiceSetting.containsAgent(current),
            AgentService.instance != null,
        )) {
            AccessibilityRecoveryPlan.NO_OP -> return
            AccessibilityRecoveryPlan.APPEND -> if (!updateServices(agentExpected = true)) return
            AccessibilityRecoveryPlan.TOGGLE_REBIND -> {
                // Finish the bounded re-add even if the listener stops after the removal write.
                val toggled = withContext(NonCancellable) {
                    val removed = updateServices(agentExpected = false)
                    // Give the settings observer time to reconcile the disabled component.
                    if (removed) delay(500)
                    val restored = updateServices(agentExpected = true)
                    if (!restored) {
                        val present = try {
                            AccessibilityServiceSetting.containsAgent(readServices().removeSuffix("\n"))
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
        if (channel.exec("settings put secure accessibility_enabled 1", SETTINGS_TIMEOUT_MS).exitCode != 0) {
            throw IOException("Cannot enable accessibility")
        }
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

    private suspend fun readServices(): String {
        val result = channel.exec(READ_SERVICES, SETTINGS_TIMEOUT_MS)
        if (result.exitCode != 0 || result.truncated || !result.stdout.endsWith('\n')) {
            throw IOException("Cannot read enabled services")
        }
        return result.stdout
    }

    private suspend fun updateServices(agentExpected: Boolean): Boolean {
        val operation = if (agentExpected) "add" else "remove"
        repeat(3) { attempt ->
            try {
                val raw = readServices()
                val current = raw.removeSuffix("\n")
                val before = AccessibilityServiceSetting.entries(current)
                val merged = if (agentExpected) AccessibilityServiceSetting.withAgent(current)
                    else AccessibilityServiceSetting.withoutAgent(current)
                if (!AccessibilityServiceSetting.verifyUpdate(before, merged, merged, agentExpected)) {
                    Log.e(TAG, "Enabled-services $operation aborted: cannot preserve existing entries")
                    return false
                }
                // Settings has no compare-and-set shell command. Compare again next to the
                // put, preserving trailing newlines with a sentinel, then verify independently.
                val command = "current=\$($READ_SERVICES && printf .) || exit 1; " +
                    "[ \"\$current\" = ${shellQuote(raw + ".")} ] || exit 2; " +
                    "settings put secure enabled_accessibility_services ${shellQuote(merged)}"
                val written = channel.exec(command, SETTINGS_TIMEOUT_MS)
                if (written.exitCode == 0 && AccessibilityServiceSetting.verifyUpdate(
                    before,
                    merged,
                    readServices().removeSuffix("\n"),
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

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun rediscover() {
        scope.launch {
            try {
                if (channel.paired) ensureRecentScan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ADB rediscovery failed: ${e.javaClass.simpleName}")
            }
        }
    }

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
    }
}

internal fun accessibilitySettingOn(app: Context): Boolean =
    AccessibilityServiceSetting.containsAgent(
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    )
