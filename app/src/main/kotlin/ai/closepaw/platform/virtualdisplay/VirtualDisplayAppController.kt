package ai.closepaw.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.AppManager
import ai.closepaw.browser.setup.ChromeFlagDeepLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** App-level operations on the virtual display (launch/list apps). */
internal class VirtualDisplayAppController(
        private val service: AccessibilityService,
        private val shizuku: ShizukuClient,
        private val displayIdProvider: () -> Int,
        /** Shelly background runs only: keeps display 0's tasks where the user left them. */
        private val mainDisplayTasks: MainDisplayTaskRestorer? = null,
        private val strictIsolation: Boolean = false,
        private val isDispatchCancelled: (() -> Boolean)? = null,
) {
        companion object {
                private const val TAG = "VDAppController"

        }

        /** Chrome's root task ids on this display, or null when they cannot be read. */
        suspend fun chromeTaskIds(): Set<Int>? {
                val displayId = displayIdProvider()
                return withContext(Dispatchers.IO) {
                        shizuku.rootTaskIdsOnDisplay(displayId, ChromeFlagDeepLink.CHROME_PACKAGE)
                }
        }

        /** Removes one task by id. Callers pass only a task they created. */
        suspend fun removeTask(taskId: Int): Boolean =
                withContext(Dispatchers.IO) { shizuku.removeRootTask(taskId) }

        /** Root tasks on the main display, the one in front first; null when unreadable. */
        suspend fun mainDisplayTasks(): List<ShizukuActivityTaskTransport.RootTask>? =
                withContext(Dispatchers.IO) { shizuku.rootTasksWithPackagesOnDisplay(Display.DEFAULT_DISPLAY) }

        /** Moves a root task (with its windows) onto this display. */
        suspend fun moveTaskHere(taskId: Int): Boolean {
                val displayId = displayIdProvider()
                return withContext(Dispatchers.IO) { shizuku.moveRootTaskToDisplay(taskId, displayId) }
        }

        /** Moves a root task back to the main display. */
        suspend fun moveTaskToMainDisplay(taskId: Int): Boolean =
                withContext(Dispatchers.IO) {
                        shizuku.moveRootTaskToDisplay(taskId, Display.DEFAULT_DISPLAY)
                }

        /** Puts a task in front on its display. */
        suspend fun moveTaskToFront(taskId: Int): Boolean =
                withContext(Dispatchers.IO) { shizuku.moveTaskToFront(taskId) }

        suspend fun getInstalledApps(): List<AppInfo> {
                return withContext(Dispatchers.IO) {
                        try {
                                AppManager.getInstalledApps(service.packageManager)
                        } catch (e: Exception) {
                                Log.e(TAG, "Failed to get installed apps", e)
                                emptyList()
                        }
                }
        }

        /** Brings Chrome onto this display without opening a tab. */
        suspend fun showChrome(): ActionResult = startChrome(emptyArray())

        /** Opens [url] in Chrome on this display, as a new tab. Argv only, no shell parsing. */
        suspend fun openUrlInChrome(url: String): ActionResult =
                startChrome(arrayOf("-a", "android.intent.action.VIEW", "-d", url))

        private suspend fun startChrome(intentArgs: Array<String>): ActionResult {
                val displayId = displayIdProvider()
                return withContext(Dispatchers.IO) {
                        val component =
                                "${ChromeFlagDeepLink.CHROME_PACKAGE}/${ChromeFlagDeepLink.CHROME_MAIN_ACTIVITY}"
                        val command =
                                arrayOf("am", "start") +
                                        intentArgs +
                                        arrayOf("-n", component, "--display", "$displayId")
                        val code = shizuku.executeQuietShellCommand(command)
                        if (code == 0) ActionResult.Success("Opened Chrome on display $displayId")
                        else ActionResult.Failure("am start failed with code $code")
                }
        }

        suspend fun launchApp(packageName: String): ActionResult {
                val displayId = displayIdProvider()
                var forceNewTask = false
                if (strictIsolation) {
                        val mainTaskIds = withContext(Dispatchers.IO) {
                                shizuku.rootTaskIdsOnDisplay(Display.DEFAULT_DISPLAY, packageName)
                        } ?: return ActionResult.Failure(
                                "Could not verify main-display tasks for an isolated session: $packageName"
                        )
                        if (mainTaskIds.isNotEmpty()) {
                                val canDuplicate = withContext(Dispatchers.IO) {
                                        canLaunchInSeparateTask(packageName)
                                } ?: return ActionResult.Failure(
                                        "Could not verify the launch activity for an isolated session: $packageName"
                                )
                                if (!canDuplicate) {
                                        return ActionResult.Failure(
                                                "App is open on the main display; refusing to move it for an isolated session: $packageName"
                                        )
                                }
                                forceNewTask = true
                        }
                }
                val restorer = mainDisplayTasks ?: return launchApp(packageName, displayId, newTask = forceNewTask)
                return try {
                        restorer.aroundLaunch(
                                packageName,
                                displayId,
                                launchGate = if (strictIsolation) { mainTasks ->
                                        if (mainTasks.any { it.packageName == packageName } &&
                                                canLaunchInSeparateTask(packageName) != true
                                        ) {
                                                "App is open on the main display; refusing to move it for an isolated session: $packageName"
                                        } else null
                                } else null,
                        ) { newTask ->
                                launchApp(packageName, displayId, forceNewTask || newTask)
                        }
                } catch (e: LaunchRefusedException) {
                        ActionResult.Failure(e.reason)
                }
        }

        private fun canLaunchInSeparateTask(packageName: String): Boolean? {
                val pm = service.packageManager
                val component = pm.getLaunchIntentForPackage(packageName)?.component ?: return null
                val activity = try {
                        pm.getActivityInfo(component, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                        return null
                }
                return when (activity.launchMode) {
                        ActivityInfo.LAUNCH_SINGLE_TASK, ActivityInfo.LAUNCH_SINGLE_INSTANCE -> false
                        ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK -> true
                        else -> activity.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NEVER
                }
        }

        /**
         * [newTask] adds FLAG_ACTIVITY_MULTIPLE_TASK to the shell launch so an app whose task is on
         * display 0 gets a separate task here instead of having that task moved off display 0
         * (singleTask / singleInstance activities ignore the flag; see [MainDisplayTaskGuard]).
         */
        private suspend fun launchApp(packageName: String, displayId: Int, newTask: Boolean): ActionResult {
                return withContext(Dispatchers.IO) {
                        try {
                                val launchIntent =
                                        service.packageManager.getLaunchIntentForPackage(packageName)
                                                ?: return@withContext ActionResult.Failure(
                                                        "App not found or not launchable: $packageName"
                                                )

                                val component = launchIntent.component?.flattenToShortString()
                                val shizukuAvailable = shizuku.isAvailable()
                                Log.d(
                                        TAG,
                                        "launchApp: component=$component, shizukuAvailable=$shizukuAvailable"
                                )

                                // The restorer's sample precedes this dispatcher hop and intent lookup.
                                // Check again on the launch thread before each possible dispatch.
                                fun requiresSeparateTask(): Boolean {
                                        if (!strictIsolation) return false
                                        val tasks = shizuku.rootTaskIdsOnDisplay(Display.DEFAULT_DISPLAY, packageName)
                                                ?: throw LaunchRefusedException(
                                                        "Could not revalidate main-display tasks for an isolated launch"
                                                )
                                        if (tasks.isEmpty()) return false
                                        if (canLaunchInSeparateTask(packageName) != true) {
                                                throw LaunchRefusedException(
                                                        "App is open on the main display; refusing to move it for an isolated session: $packageName"
                                                )
                                        }
                                        return true
                                }

                                var dispatchedMessage: String? = null
                                if (component != null && shizukuAvailable) {
                                        Log.d(TAG, "Launching $component on display $displayId via shell")
                                        val mustDuplicate = requiresSeparateTask()
                                        val cmd =
                                                arrayOf(
                                                        "am",
                                                        "start",
                                                        "-n",
                                                        component,
                                                        "--display",
                                                        "$displayId"
                                                ) + (if (newTask || mustDuplicate) arrayOf("--activity-multiple-task") else emptyArray<String>())
                                        if (isDispatchCancelled?.invoke() == true) {
                                                return@withContext ActionResult.Cancelled("Cancelled before launch")
                                        }
                                        val code = shizuku.executeShellCommand(cmd)
                                        if (code == 0) {
                                                dispatchedMessage = "Launched $component on display $displayId (shell)"
                                        } else {
                                                Log.w(
                                                        TAG,
                                                        "Shell launch failed (code $code), falling back to intent"
                                                )
                                        }
                                }

                                if (dispatchedMessage == null) {
                                        launchIntent.addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                        )
                                        requiresSeparateTask()
                                        if (isDispatchCancelled?.invoke() == true) {
                                                return@withContext ActionResult.Cancelled("Cancelled before launch")
                                        }
                                        shizuku.launchOnDisplay(service, launchIntent, displayId)
                                        dispatchedMessage = "Launched $packageName on display $displayId (intent)"
                                }

                                verifyLaunchSurfaced(packageName, displayId)?.let { return@withContext it }
                                ActionResult.Success(dispatchedMessage)
                        } catch (e: LaunchRefusedException) {
                                ActionResult.Failure(e.reason)
                        } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
                                ActionResult.Failure("Failed to launch $packageName: ${e.message}")
                        }
                }
        }

        /**
         * An `am start` exit code of 0 confirms intent delivery. When the app has an existing
         * task, Android can reparent it to the virtual display while leaving another task on top.
         * Require an observation of the target in front before reporting launch success.
         * [LaunchSurfacingLimits.TIMEOUT_MS] is a wall-clock window on the elapsed-time clock.
         * No observation starts at or after the deadline, and observations completed at or
         * after it are discarded. Verification may return after the deadline by at most the
         * latency of one observation or move already in flight when the deadline passed.
         */
        private suspend fun verifyLaunchSurfaced(packageName: String, displayId: Int): ActionResult? {
                val deadlineMs = LaunchSurfacing.deadlineMs(SystemClock.elapsedRealtime())
                val cancelledMessage = "Cancelled while verifying launch of $packageName"
                var sawTargetTask = false
                var sawReadableObservation = false
                while (true) {
                        if (isDispatchCancelled?.invoke() == true) {
                                return ActionResult.Cancelled(cancelledMessage)
                        }
                        if (!LaunchSurfacing.mayStartOperation(SystemClock.elapsedRealtime(), deadlineMs)) break

                        val tasks = shizuku.rootTasksOnDisplay(displayId)
                        val completedMs = SystemClock.elapsedRealtime()
                        if (!LaunchSurfacing.observationCounts(completedMs, deadlineMs)) break
                        if (tasks != null) {
                                sawReadableObservation = true
                                when (val assessment = LaunchSurfacing.assess(tasks, packageName)) {
                                        LaunchSurfacingAssessment.Surfaced -> return null
                                        is LaunchSurfacingAssessment.PresentNotFront -> {
                                                sawTargetTask = true
                                                if (!LaunchSurfacing.mayStartOperation(SystemClock.elapsedRealtime(), deadlineMs)) break
                                                moveTaskToFront(assessment.taskId)
                                        }
                                        LaunchSurfacingAssessment.Absent -> Unit
                                }
                        }

                        val delayMs = LaunchSurfacing.nextPollDelayMs(SystemClock.elapsedRealtime(), deadlineMs)
                        if (delayMs <= 0) break
                        delay(delayMs)
                        if (isDispatchCancelled?.invoke() == true) {
                                return ActionResult.Cancelled(cancelledMessage)
                        }
                }

                if (isDispatchCancelled?.invoke() == true) {
                        return ActionResult.Cancelled(cancelledMessage)
                }
                val message = when (LaunchSurfacing.failureKind(sawTargetTask, sawReadableObservation)) {
                        LaunchSurfacingFailure.NEVER_APPEARED ->
                                "Launch delivered but $packageName never appeared on display $displayId"
                        LaunchSurfacingFailure.NOT_BROUGHT_TO_FRONT ->
                                "Launch delivered but $packageName did not come to the front on display $displayId"
                        LaunchSurfacingFailure.UNVERIFIABLE ->
                                "Launch delivered but surfacing of $packageName on display $displayId could not be verified"
                }
                return ActionResult.Failure(message)
        }
}
