package ai.closepaw.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
        private val displayIdProvider: () -> Int
) {
        companion object {
                private const val TAG = "VDAppController"

                /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_NEW_DOCUMENT. */
                internal const val NEW_WINDOW_FLAGS = "0x18080000"
                private const val WINDOW_CHECKS = 10
                private const val WINDOW_CHECK_MS = 300L
        }

        /** Where [openChromeWindow] put Chrome. */
        enum class ChromeWindowLaunch { FAILED, ON_DISPLAY, ELSEWHERE }

        /**
         * [launch]; for [ChromeWindowLaunch.ON_DISPLAY] the Chrome task ids that appeared, and
         * every Chrome task id that existed before, on this display or the main one (null when
         * either list could not be read).
         */
        data class ChromeWindow(
                val launch: ChromeWindowLaunch,
                val createdTaskIds: Set<Int> = emptySet(),
                val preexistingTaskIds: Set<Int>? = null,
        )

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

        /**
         * Opens [url] in a new Chrome window task on this display. Chrome's launcher activity is a
         * trampoline: a plain `am start --display` brings Chrome's existing task forward wherever it
         * is, so Chrome lands on the owner's screen. The multiple-task flags ask for a new task,
         * which `--display` can place. Whether Chrome makes one depends on its version and the
         * device, so the result is checked: a Chrome root task on this display that was not there
         * before. Argv only, no shell parsing; the URL is not logged.
         */
        suspend fun openChromeWindow(url: String): ChromeWindow {
                val displayId = displayIdProvider()
                return withContext(Dispatchers.IO) {
                        val chrome = ChromeFlagDeepLink.CHROME_PACKAGE
                        val before = shizuku.rootTaskIdsOnDisplay(displayId, chrome)
                        val beforeMain = shizuku.rootTaskIdsOnDisplay(Display.DEFAULT_DISPLAY, chrome)
                        val preexisting = if (before != null && beforeMain != null) before + beforeMain else null
                        val component = "$chrome/${ChromeFlagDeepLink.CHROME_MAIN_ACTIVITY}"
                        val command =
                                arrayOf(
                                        "am", "start",
                                        "-f", NEW_WINDOW_FLAGS,
                                        "--display", "$displayId",
                                        "-a", "android.intent.action.VIEW",
                                        "-d", url,
                                        "-n", component,
                                )
                        val code = shizuku.executeQuietShellCommand(command)
                        if (code != 0) {
                                Log.i(TAG, "chrome window: am start failed code=$code")
                                return@withContext ChromeWindow(ChromeWindowLaunch.FAILED)
                        }
                        if (before == null) {
                                Log.i(TAG, "chrome window: tasks on display $displayId unreadable")
                                return@withContext ChromeWindow(ChromeWindowLaunch.ELSEWHERE)
                        }
                        repeat(WINDOW_CHECKS) {
                                val now = shizuku.rootTaskIdsOnDisplay(displayId, chrome).orEmpty()
                                val created = now - before
                                if (created.isNotEmpty()) {
                                        Log.i(TAG, "chrome window: new task on display $displayId tasks=$created")
                                        return@withContext ChromeWindow(ChromeWindowLaunch.ON_DISPLAY, created, preexisting)
                                }
                                delay(WINDOW_CHECK_MS)
                        }
                        Log.i(TAG, "chrome window: no new task on display $displayId; Chrome opened it elsewhere")
                        ChromeWindow(ChromeWindowLaunch.ELSEWHERE)
                }
        }

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

                                if (component != null && shizukuAvailable) {
                                        Log.d(TAG, "Launching $component on display $displayId via shell")
                                        val cmd =
                                                arrayOf(
                                                        "am",
                                                        "start",
                                                        "-n",
                                                        component,
                                                        "--display",
                                                        "$displayId"
                                                )
                                        val code = shizuku.executeShellCommand(cmd)
                                        if (code == 0) {
                                                return@withContext ActionResult.Success(
                                                        "Launched $component on display $displayId (shell)"
                                                )
                                        }
                                        Log.w(
                                                TAG,
                                                "Shell launch failed (code $code), falling back to intent"
                                        )
                                }

                                launchIntent.addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                )
                                shizuku.launchOnDisplay(service, launchIntent, displayId)
                                ActionResult.Success(
                                        "Launched $packageName on display $displayId (intent)"
                                )
                        } catch (e: Exception) {
                                Log.e(TAG, "Failed to launch $packageName on display $displayId", e)
                                ActionResult.Failure("Failed to launch $packageName: ${e.message}")
                        }
                }
        }
}
