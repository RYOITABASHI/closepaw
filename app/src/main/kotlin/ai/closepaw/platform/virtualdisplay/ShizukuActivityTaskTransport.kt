package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.StartActivityAsUserMethod
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import java.lang.reflect.InvocationTargetException

/** Transport layer for display-scoped task cleanup through IActivityTaskManager. */
internal class ShizukuActivityTaskTransport(
        private val activityTaskManagerProxy: () -> Any
) {
        constructor(proxyProvider: ShizukuServiceProxyProvider) : this(
                { proxyProvider.activityTaskManagerProxy() }
        )

        companion object {
                private const val TAG = "ShizukuActivityTask"
                /** WindowConfiguration.WINDOWING_MODE_PINNED (hidden). */
                private const val WINDOWING_MODE_PINNED = 2
                /** Calls through Shizuku run as the shell user, whose package this is. */
                private const val SHELL_PACKAGE = "com.android.shell"
                /** UserHandle.USER_CURRENT (hidden): `am start`'s default target user. */
                private const val USER_CURRENT = -2

                /**
                 * Whether a binder start result is a success: ActivityManager's START_* success
                 * codes are 0..99 (START_SUCCESS .. START_DELIVERED_TO_TOP and friends). Negative
                 * codes are errors, and codes >= 100 (START_SWITCHES_CANCELED = 100,
                 * START_RETURN_LOCK_TASK_MODE_VIOLATION = 101; ActivityManager's fatal-error
                 * threshold) mean nothing was started either.
                 */
                internal fun isStartSuccess(result: Int): Boolean = result in 0..99
        }

        fun removeRootTasksOnDisplay(displayId: Int, keepTaskIds: Set<Int> = emptySet()): Int {
                if (displayId < 0) {
                        Log.w(
                                TAG,
                                "VD task cleanup before release: displayId=$displayId removed=-1 err=invalid-display"
                        )
                        return -1
                }

                return try {
                        val proxy = activityTaskManagerProxy()
                        val rootTaskInfos = getAllRootTaskInfosOnDisplay(proxy, displayId)
                        var removed = 0

                        rootTaskInfos.forEach { info ->
                                val taskId = rootTaskId(info)
                                if (taskId in keepTaskIds) {
                                        // A user task that could not be moved back: let the
                                        // display release hand it back instead of destroying it.
                                        Log.w(TAG, "VD task cleanup: keeping displaced task $taskId")
                                } else if (removeTask(proxy, taskId)) {
                                        removed++
                                }
                        }

                        Log.i(
                                TAG,
                                "VD task cleanup before release: displayId=$displayId removed=$removed ok"
                        )
                        removed
                } catch (e: Exception) {
                        Log.w(
                                TAG,
                                "VD task cleanup before release: displayId=$displayId removed=-1 err=${e.summary()}",
                                e
                        )
                        -1
                }
        }

        /**
         * Root tasks on [displayId], top-most first (WindowManager's traversal order), or null
         * when the query fails (e.g. pre-Android 12 where the method does not exist).
         */
        fun rootTasksOnDisplay(displayId: Int): List<DisplayTask>? {
                if (displayId < 0) return null
                return try {
                        getAllRootTaskInfosOnDisplay(activityTaskManagerProxy(), displayId)
                                .map { toDisplayTask(it) }
                } catch (e: Exception) {
                        Log.w(TAG, "root task query failed: displayId=$displayId err=${e.summary()}")
                        null
                }
        }

        /**
         * Save-draft routine: ids of the root tasks on [displayId] whose base, top or real
         * activity belongs to [packageName]; null when the transport fails.
         */
        fun rootTaskIdsOnDisplay(displayId: Int, packageName: String): Set<Int>? {
                if (displayId < 0) return null
                return try {
                        getAllRootTaskInfosOnDisplay(activityTaskManagerProxy(), displayId)
                                .filter { packageName in taskPackages(it) }
                                .map { rootTaskId(it) }
                                .toSet()
                } catch (e: Exception) {
                        Log.w(TAG, "root task query failed: displayId=$displayId err=${e.summary()}")
                        null
                }
        }

        /** A root task with every package of its activities and its visibility (save-draft routine). */
        data class RootTask(val id: Int, val packages: Set<String>, val visible: Boolean?)

        /**
         * Save-draft routine: root tasks on [displayId] with all their packages and visibility,
         * front first; null when the transport fails. (The shared VD guard code uses
         * [rootTasksOnDisplay] / [DisplayTask].)
         */
        fun rootTasksWithPackagesOnDisplay(displayId: Int): List<RootTask>? {
                if (displayId < 0) return null
                return try {
                        getAllRootTaskInfosOnDisplay(activityTaskManagerProxy(), displayId).map { info ->
                                val visible =
                                        runCatching { info.javaClass.getField("visible").getBoolean(info) }
                                                .getOrNull()
                                RootTask(rootTaskId(info), taskPackages(info), visible)
                        }
                } catch (e: Exception) {
                        Log.w(TAG, "root task list failed: displayId=$displayId err=${e.summary()}")
                        null
                }
        }

        /** Move a root task to [displayId] (lands on top of it). */
        fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean {
                return try {
                        val proxy = activityTaskManagerProxy()
                        proxy.javaClass
                                .getMethod(
                                        "moveRootTaskToDisplay",
                                        Int::class.javaPrimitiveType,
                                        Int::class.javaPrimitiveType
                                )
                                .invoke(proxy, taskId, displayId)
                        true
                } catch (e: Exception) {
                        Log.w(TAG, "moveRootTaskToDisplay($taskId, $displayId) failed: ${e.summary()}")
                        false
                }
        }

        /**
         * Bring an existing (non-home) task to the front, as the Recents screen does. [options]:
         * ActivityOptions bundle (e.g. no animation); null = platform default transition.
         */
        fun startActivityFromRecents(taskId: Int, options: android.os.Bundle? = null): Boolean {
                return try {
                        val proxy = activityTaskManagerProxy()
                        val result =
                                proxy.javaClass
                                        .getMethod(
                                                "startActivityFromRecents",
                                                Int::class.javaPrimitiveType,
                                                android.os.Bundle::class.java
                                        )
                                        .invoke(proxy, taskId, options) as? Int ?: -1
                        // Recents may delegate to an activity start, so the same START_* range
                        // applies: 0..99 (incl. START_TASK_TO_FRONT) only; 101 = lock-task violation.
                        val ok = isStartSuccess(result)
                        if (!ok) Log.w(TAG, "startActivityFromRecents($taskId) returned $result")
                        ok
                } catch (e: Exception) {
                        Log.w(TAG, "startActivityFromRecents($taskId) failed: ${e.summary()}")
                        false
                }
        }

        /**
         * Save-draft routine: puts a task in front on its display via
         * IActivityTaskManager.moveTaskToFront as the shell, or [startActivityFromRecents] where
         * that signature is not there.
         */
        fun moveTaskToFront(taskId: Int): Boolean {
                val proxy = try {
                        activityTaskManagerProxy()
                } catch (e: Exception) {
                        Log.w(TAG, "task to front failed: taskId=$taskId err=${e.summary()}")
                        return false
                }
                val toFront = proxy.javaClass.methods.firstOrNull { m ->
                        m.name == "moveTaskToFront" && m.parameterTypes.size == 5 &&
                                m.parameterTypes[1] == String::class.java &&
                                m.parameterTypes[2] == Int::class.javaPrimitiveType
                }
                if (toFront != null) {
                        try {
                                toFront.invoke(proxy, null, SHELL_PACKAGE, taskId, 0, null)
                                Log.i(TAG, "task $taskId to front")
                                return true
                        } catch (e: Exception) {
                                Log.w(TAG, "moveTaskToFront failed: taskId=$taskId err=${e.summary()}")
                        }
                }
                return startActivityFromRecents(taskId, null)
        }

        /**
         * Start [intent] as the shell for the CURRENT user, as `am start` does (its default user
         * is USER_CURRENT), over binder instead of a process spawn. [options] carries e.g. the
         * launch display. Uses IActivityTaskManager.startActivityAsUser, looked up by its exact signature: startActivityAsUser(caller, callingPackage,
         * callingFeatureId, intent, resolvedType, resultTo, resultWho, requestCode, flags,
         * profilerInfo, options, userId) since Android 11. Plain startActivity would resolve the
         * shell's own user (0), which is wrong on a secondary-user session.
         */
        fun startActivity(intent: android.content.Intent, options: android.os.Bundle?): Boolean {
                return try {
                        val proxy = activityTaskManagerProxy()
                        val method = StartActivityAsUserMethod.resolve(proxy.javaClass)
                        val result =
                                method.invoke(
                                        proxy,
                                        null, // caller
                                        SHELL_PACKAGE, // callingPackage (must match the shell uid)
                                        null, // callingFeatureId
                                        intent,
                                        null, // resolvedType
                                        null, // resultTo
                                        null, // resultWho
                                        0, // requestCode
                                        0, // flags
                                        null, // profilerInfo
                                        options,
                                        USER_CURRENT
                                ) as? Int ?: -1
                        val ok = isStartSuccess(result)
                        if (!ok) Log.w(TAG, "startActivity(${intent.action}) returned $result")
                        ok
                } catch (e: Exception) {
                        Log.w(TAG, "startActivity(${intent.action}) failed: ${e.summary()}")
                        false
                }
        }

        /** Save-draft routine: removes one task by id. False when not removed or the transport failed. */
        fun removeRootTaskResult(taskId: Int): Boolean? {
                return try {
                        removeTask(activityTaskManagerProxy(), taskId)
                } catch (e: Exception) {
                        Log.w(TAG, "remove task failed: taskId=$taskId err=${e.summary()}")
                        null
                }
        }

        private fun toDisplayTask(info: Any): DisplayTask {
                val taskId = rootTaskId(info)
                val pkg =
                        (fieldOrNull(info, "topActivity") as? android.content.ComponentName)?.packageName
                                ?: (fieldOrNull(info, "baseActivity") as? android.content.ComponentName)
                                        ?.packageName
                return DisplayTask(taskId = taskId, packageName = pkg, pinned = windowingMode(info) == WINDOWING_MODE_PINNED)
        }

        private fun fieldOrNull(target: Any, name: String): Any? =
                try {
                        target.javaClass.getField(name).get(target)
                } catch (_: Exception) {
                        null
                }

        private fun windowingMode(info: Any): Int? {
                return try {
                        val config = fieldOrNull(info, "configuration") ?: return null
                        val windowConfig = config.javaClass.getField("windowConfiguration").get(config) ?: return null
                        windowConfig.javaClass.getMethod("getWindowingMode").invoke(windowConfig) as? Int
                } catch (_: Exception) {
                        null
                }
        }

        private fun taskPackages(info: Any): Set<String> {
                val packages = mutableSetOf<String>()
                for (name in listOf("baseActivity", "topActivity", "realActivity", "origActivity")) {
                        val component = fieldOrNull(info, name)
                        (component as? ComponentName)?.packageName?.let(packages::add)
                }
                (fieldOrNull(info, "baseIntent") as? Intent)?.component?.packageName?.let(packages::add)
                return packages
        }

        private fun getAllRootTaskInfosOnDisplay(proxy: Any, displayId: Int): List<Any> {
                val method =
                        proxy.javaClass.getMethod(
                                "getAllRootTaskInfosOnDisplay",
                                Int::class.javaPrimitiveType
                        )
                val result = method.invoke(proxy, displayId) ?: return emptyList()
                return (result as? List<*>)
                        ?.filterNotNull()
                        ?: throw IllegalStateException(
                                "getAllRootTaskInfosOnDisplay returned ${result.javaClass.name}"
                        )
        }

        private fun removeTask(proxy: Any, taskId: Int): Boolean {
                val method =
                        proxy.javaClass.getMethod(
                                "removeTask",
                                Int::class.javaPrimitiveType
                        )
                return method.invoke(proxy, taskId) as Boolean
        }

        private fun rootTaskId(rootTaskInfo: Any): Int {
                return rootTaskInfo.javaClass.getField("taskId").getInt(rootTaskInfo)
        }

        private fun Throwable.summary(): String {
                val cause = if (this is InvocationTargetException) targetException else this
                val name = cause::class.java.simpleName
                val message = cause.message
                return if (message.isNullOrBlank()) name else "$name: $message"
        }
}
