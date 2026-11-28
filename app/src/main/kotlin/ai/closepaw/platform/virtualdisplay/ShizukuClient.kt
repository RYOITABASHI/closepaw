package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperWire
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import android.view.Surface
import rikka.shizuku.Shizuku

/**
 * ShizukuClient — Thin wrapper for Shizuku binder calls.
 *
 * Uses the session task helper for observation when attached, with Shizuku fallback.
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
    private val taskRead = RoutedTaskRead(onSource = { Log.d(TAG, "Task read source=$it") })
    @Volatile private var taskHelperWriter: ((Int, Bundle) -> TaskWriteResult)? = null

    internal fun attachTaskHelperWriter(writer: (Int, Bundle) -> TaskWriteResult, available: () -> Boolean) {
        taskHelperWriter = writer
        taskHelperAvailable = available
    }

    private fun helperRouteEnabled() = TaskObservationPolicy.useHelperRoute(
            Build.VERSION.SDK_INT, TaskObservationPolicy.HELPER_ROUTE_ENABLED, true,
    )

    private fun taskWrite(
            code: Int,
            taskId: Int? = null,
            displayId: Int? = null,
            intent: Intent? = null,
            options: Bundle? = null,
            fallback: () -> Boolean?,
    ): Boolean? {
        val writer = taskHelperWriter
        val result = if (writer == null || !helperRouteEnabled()) TaskWriteResult.FAILED
        else writer(code, Bundle().apply {
            taskId?.let { putInt(TaskHelperWire.TASK_ID, it) }
            displayId?.let { putInt(TaskHelperWire.DISPLAY_ID, it) }
            intent?.let { putParcelable(TaskHelperWire.INTENT, it) }
            options?.let { putBundle(TaskHelperWire.OPTIONS, it) }
        })
        Log.d(TAG, "Task write command=$code helperResult=$result")
        return result.resolve(retryNegative = code != TaskHelperWire.REMOVE_TASK) {
            fallback().also { Log.d(TAG, "Task write command=$code Shizuku result=$it") }
        }
    }

    @Volatile private var taskHelperSource: HelperRowSource? = null

    internal fun attachTaskHelper(source: HelperRowSource?) {
        taskHelperSource = source
    }

    internal fun hasTaskHelper() = taskHelperAvailable?.invoke() == true

    @Volatile private var taskHelperAvailable: (() -> Boolean)? = null

    internal fun helperRows(displayId: Int, sdkInt: Int = Build.VERSION.SDK_INT): List<TaskHelperRow>? =
            taskRead.helperRows(
                    displayId, sdkInt,
                    TaskObservationPolicy.HELPER_ROUTE_ENABLED, taskHelperSource,
            )

    private fun <T> servedRead(
            displayId: Int,
            incumbent: () -> T?,
            mapRows: (List<TaskHelperRow>) -> T,
            requireHelper: Boolean = false,
    ): Served<T>? = taskRead.served(
            displayId, Build.VERSION.SDK_INT,
            TaskObservationPolicy.HELPER_ROUTE_ENABLED, taskHelperSource,
            incumbent, requireHelper, mapRows,
    )


    // ── Shizuku Status ──────────────────────────────────────────

    /** True if Shizuku binder is alive and responding. */
    fun isAvailable(): Boolean =
            runtimeGateway.isAvailable()

    internal fun serverUidOrNull(): Int? = runtimeGateway.serverUidOrNull()

    /** True if we have been granted Shizuku permission. */
    fun hasPermission(): Boolean =
            runtimeGateway.hasPermission()

    internal fun permissionGrantedOrNull(): Boolean? = runtimeGateway.permissionGrantedOrNull()

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

    /** Register a listener for a (re)delivered Shizuku binder. */
    internal fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        runtimeGateway.addBinderReceivedListener(listener)
    }

    internal fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        runtimeGateway.removeBinderReceivedListener(listener)
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
            imePolicy: Int? = null,
            optionalFlags: Int = 0
    ): Int {
        return displayTransport.createVirtualDisplay(
                name, width, height, densityDpi, surface, flags, imePolicy, optionalFlags
        )
    }

    /**
     * Switch the surface a virtual display renders to.
     *
     * Uses IDisplayManager.setVirtualDisplaySurface(callback, displayId, surface). The callback
     * token must match the one used in createVirtualDisplay.
     *
     * @return true if the surface was switched successfully
     */
    internal fun setHelperDisplaySurface(token: IBinder, surface: Surface): Boolean =
            displayTransport.setHelperDisplaySurface(token, surface)

    internal fun releaseHelperDisplay(token: IBinder): Boolean =
            displayTransport.releaseHelperDisplay(token)

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
        if (!helperRouteEnabled()) return activityTaskTransport.removeRootTasksOnDisplay(displayId, keepTaskIds)
        if (displayId < 0) return -1
        val taskIds = rootTasksOnDisplay(displayId)?.map { it.taskId } ?: return -1
        var removed = 0
        var unconfirmed = false
        for (taskId in taskIds) {
            if (taskId in keepTaskIds) continue
            when (removeTaskResult(taskId)) {
                true -> removed++
                false -> Log.d(TAG, "VD task cleanup: task $taskId was absent")
                null -> {
                    unconfirmed = true
                    Log.w(TAG, "VD task cleanup: removal of task $taskId unconfirmed")
                }
            }
        }
        return if (unconfirmed) -1 else removed
    }

    private val lastRootTaskSource = ThreadLocal<ServedSource?>()

    /** Root tasks on a display, top-most first; null when the query fails. */
    internal fun rootTasksOnDisplay(displayId: Int): List<DisplayTask>? =
            rootTasksOnDisplaySourced(displayId)?.tasks

    internal fun rootTasksOnDisplaySourced(displayId: Int): SourcedDisplayTasks? =
            servedRead(
                    displayId,
                    incumbent = { rootTasksOnDisplayIncumbent(displayId) },
                    mapRows = TaskRowMapping::toDisplayTasks,
            )?.let {
                lastRootTaskSource.set(it.source)
                SourcedDisplayTasks(it.value, it.source)
            }

    /**
     * Source of the calling thread's most recent non-null sourced root-task read,
     * or null if that thread has had no such read. This lets the served answer
     * travel through the baseline-named [rootTasksOnDisplay] while keeping source
     * attribution exact for each read. The tag must never influence a served answer.
     */
    internal fun lastRootTaskReadSource(): ServedSource? = lastRootTaskSource.get()

    internal fun rootTasksOnDisplayIncumbent(displayId: Int): List<DisplayTask>? =
            activityTaskTransport.rootTasksOnDisplay(displayId)

    /** Move a root task to a display (it lands on top). */
    fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean =
            taskWrite(TaskHelperWire.MOVE_ROOT_TASK_TO_DISPLAY, taskId, displayId) {
                activityTaskTransport.moveRootTaskToDisplay(taskId, displayId)
            } == true

    /** Bring an existing non-home task to the front (Recents semantics); [options] = ActivityOptions bundle. */
    fun startActivityFromRecents(taskId: Int, options: android.os.Bundle? = null): Boolean =
            taskWrite(TaskHelperWire.START_ACTIVITY_FROM_RECENTS, taskId, options = options) {
                activityTaskTransport.startActivityFromRecents(taskId, options)
            } == true

    /** Start an intent as the shell over binder (what `am start` does, without a process spawn). */
    fun startActivityAsShell(intent: Intent, options: android.os.Bundle?): Boolean =
            taskWrite(TaskHelperWire.START_ACTIVITY_AS_USER, intent = intent, options = options) {
                activityTaskTransport.startActivity(intent, options)
            } == true

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

    // ── Save-draft routine (owner's Chrome staging) ─────────────

    /** Removes one task by id. */
    fun removeRootTask(taskId: Int): Boolean = removeTaskResult(taskId) == true

    private fun removeTaskResult(taskId: Int): Boolean? =
            taskWrite(TaskHelperWire.REMOVE_TASK, taskId) { activityTaskTransport.removeRootTaskResult(taskId) }

    internal fun rootTasksWithPackagesOnDisplayIncumbent(displayId: Int) =
            activityTaskTransport.rootTasksWithPackagesOnDisplay(displayId)

    /** Puts a task in front on its display. */
    fun moveTaskToFront(taskId: Int): Boolean = taskWrite(TaskHelperWire.MOVE_TASK_TO_FRONT, taskId) {
                activityTaskTransport.moveTaskToFront(taskId)
            } == true

    /** Root task ids of [packageName] on [displayId], or null when they cannot be read. */
    fun rootTaskIdsOnDisplay(displayId: Int, packageName: String): Set<Int>? =
            rootTaskIdsOnDisplay(displayId, packageName, requireHelper = false)

    internal fun rootTaskIdsOnDisplay(
            displayId: Int, packageName: String, requireHelper: Boolean,
    ): Set<Int>? =
            servedRead(
                    displayId,
                    incumbent = { activityTaskTransport.rootTaskIdsOnDisplay(displayId, packageName) },
                    mapRows = { rows -> TaskRowMapping.toTaskIds(rows, packageName) },
                    requireHelper = requireHelper,
            )?.value

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

    /** [executeShellCommand] for a command that must not reach logcat. */
    fun executeQuietShellCommand(command: Array<String>): Int {
        return shellExecutor.execute(command, quiet = true)
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
