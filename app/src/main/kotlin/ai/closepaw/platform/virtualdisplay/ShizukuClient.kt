package ai.closepaw.platform.virtualdisplay

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import rikka.shizuku.Shizuku

/**
 * ShizukuClient — Thin wrapper for Shizuku binder calls.
 *
 * Every public method is a direct proxy to a system service through ShizukuBinderWrapper. No
 * caching, no business logic, no cleverness.
 *
 * Uses reflection on the framework's own IDisplayManager/IInputManager stubs (via
 * ShizukuBinderWrapper) so transaction IDs always match the device's framework version. No custom
 * AIDL files needed.
 */
class ShizukuClient {

    companion object {
        private const val TAG = "ShizukuClient"

        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH */
        const val INJECT_MODE_WAIT = 2
    }

    private val runtimeGateway = ShizukuRuntimeGateway()

    // ── Shizuku Status ──────────────────────────────────────────

    /** True if Shizuku binder is alive and responding. */
    fun isAvailable(): Boolean =
            runtimeGateway.isAvailable()

    /** True if we have been granted Shizuku permission. */
    fun hasPermission(): Boolean =
            runtimeGateway.hasPermission()

    /** Request Shizuku permission from the user. */
    fun requestPermission(requestCode: Int) {
        runtimeGateway.requestPermission(requestCode)
    }

    /** Register a listener for Shizuku binder death. */
    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        runtimeGateway.addBinderDeadListener(listener)
    }

    /** Remove a previously registered binder death listener. */
    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        runtimeGateway.removeBinderDeadListener(listener)
    }

    /** Register a listener for Shizuku permission request results. */
    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runtimeGateway.addRequestPermissionResultListener(listener)
    }

    /** Remove a previously registered permission result listener. */
    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runtimeGateway.removeRequestPermissionResultListener(listener)
    }

    // ── Hidden API Bypass ───────────────────────────────────────

    /**
     * Exempt all hidden APIs for this process. Must be called before any reflection on framework
     * internals.
     */
    fun bypassHiddenApis() {
        runtimeGateway.bypassHiddenApis()
    }

    // ── Display Management ──────────────────────────────────────

    private val shellExecutor = ShizukuShellExecutor()
    private val proxyProvider = ShizukuServiceProxyProvider()
    private val displayTransport = ShizukuDisplayTransport(proxyProvider)
    private val inputTransport = ShizukuInputTransport(proxyProvider)
    private val activityTaskTransport = ShizukuActivityTaskTransport(proxyProvider)
    private val activityLauncher = ShizukuActivityLauncher()
    private val windowTransport = ShizukuWindowTransport(proxyProvider)

    /**
     * Create a virtual display via IDisplayManager through Shizuku.
     *
     * [imePolicy] (WindowManager.DISPLAY_IME_POLICY_*): applied atomically at creation when the
     * API 33+ VirtualDisplayConfig.Builder supports it (see [imePolicyAppliedAtCreation]);
     * otherwise ignored here and left to [setDisplayImePolicy].
     *
     * @return displayId of the created virtual display, or -1 on failure
     */
    fun createVirtualDisplay(
            name: String,
            width: Int,
            height: Int,
            densityDpi: Int,
            surface: Surface,
            flags: Int,
            imePolicy: Int? = null
    ): Int {
        return displayTransport.createVirtualDisplay(name, width, height, densityDpi, surface, flags, imePolicy)
    }

    /**
     * Switch the surface a virtual display renders to.
     *
     * Uses IDisplayManager.setVirtualDisplaySurface(callback, displayId, surface). The callback
     * token must match the one used in createVirtualDisplay.
     *
     * @return true if the surface was switched successfully
     */
    fun setVirtualDisplaySurface(displayId: Int, surface: Surface): Boolean {
        return displayTransport.setVirtualDisplaySurface(displayId, surface)
    }

    /** Release a virtual display. */
    fun releaseVirtualDisplay(displayId: Int) {
        displayTransport.releaseVirtualDisplay(displayId)
    }

    /**
     * Remove root tasks currently attached to a display before releasing that display.
     *
     * @return number of root tasks removed, or -1 when the transport fails
     */
    fun removeRootTasksOnDisplay(displayId: Int, keepTaskIds: Set<Int> = emptySet()): Int {
        return activityTaskTransport.removeRootTasksOnDisplay(displayId, keepTaskIds)
    }

    /** Root tasks on a display, top-most first; null when the query fails. */
    internal fun rootTasksOnDisplay(displayId: Int): List<DisplayTask>? =
            activityTaskTransport.rootTasksOnDisplay(displayId)

    /** Move a root task to a display (it lands on top). */
    fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean =
            activityTaskTransport.moveRootTaskToDisplay(taskId, displayId)

    /** Bring an existing non-home task to the front (Recents semantics); [options] = ActivityOptions bundle. */
    fun startActivityFromRecents(taskId: Int, options: android.os.Bundle? = null): Boolean =
            activityTaskTransport.startActivityFromRecents(taskId, options)

    /** Start an intent as the shell over binder (what `am start` does, without a process spawn). */
    fun startActivityAsShell(intent: Intent, options: android.os.Bundle?): Boolean =
            activityTaskTransport.startActivity(intent, options)

    // ── Window Manager ──────────────────────────────────────────

    /**
     * Set a display's IME policy (WindowManager.DISPLAY_IME_POLICY_*). Fail-open: returns false
     * and logs when the hidden API is missing or the caller lacks permission.
     */
    fun setDisplayImePolicy(displayId: Int, imePolicy: Int): Boolean =
            windowTransport.setDisplayImePolicy(displayId, imePolicy)

    /** Whether the display was created with an IME policy already applied (API 33+ config builder). */
    fun imePolicyAppliedAtCreation(displayId: Int): Boolean =
            displayTransport.imePolicyAppliedAtCreation(displayId)

    // ── Input Injection ─────────────────────────────────────────

    /**
     * Inject an input event via IInputManager through Shizuku.
     *
     * The event must have displayId set before calling this. Uses
     * INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH for synchronous delivery.
     *
     * @return true if injection succeeded
     */
    fun injectInputEvent(event: InputEvent, mode: Int = INJECT_MODE_WAIT): Boolean {
        return inputTransport.injectInputEvent(event, mode)
    }

    // ── App Launch ──────────────────────────────────────────────

    /**
     * Launch an activity on a specific display.
     *
     * Uses ActivityOptions.setLaunchDisplayId() which requires shell permission for non-default
     * displays.
     */
    fun launchOnDisplay(context: Context, intent: Intent, displayId: Int) {
        activityLauncher.launchOnDisplay(context, intent, displayId)
    }

    /**
     * Execute a shell command via Shizuku.
     *
     * Prefers Shizuku.newProcess() direct API when available; falls back to reflection for
     * compatibility. Logs non-zero exit codes and exceptions.
     *
     * @return Exit code of the command, or -1 on failure
     */
    fun executeShellCommand(command: Array<String>): Int {
        return shellExecutor.execute(command)
    }

    // ── Proxy Lifecycle ──────────────────────────────────────────

    /**
     * Clear cached binder proxies. Call during platform stop/cleanup. Safe to call even if proxies
     * were never created.
     */
    fun clearCachedProxies() {
        proxyProvider.clear()
        displayTransport.clear()
        Log.d(TAG, "Cleared cached binder proxies")
    }

    // ── Private: Binder Proxy Acquisition ───────────────────────

}
