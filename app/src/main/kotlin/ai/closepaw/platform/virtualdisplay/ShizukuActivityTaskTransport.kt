package ai.closepaw.platform.virtualdisplay

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

        /** Bring an existing (non-home) task to the front, as the Recents screen does. */
        fun startActivityFromRecents(taskId: Int): Boolean {
                return try {
                        val proxy = activityTaskManagerProxy()
                        val result =
                                proxy.javaClass
                                        .getMethod(
                                                "startActivityFromRecents",
                                                Int::class.javaPrimitiveType,
                                                android.os.Bundle::class.java
                                        )
                                        .invoke(proxy, taskId, null) as? Int ?: -1
                        // ActivityManager START_* codes: >= 0 is success (incl. START_TASK_TO_FRONT).
                        if (result < 0) Log.w(TAG, "startActivityFromRecents($taskId) returned $result")
                        result >= 0
                } catch (e: Exception) {
                        Log.w(TAG, "startActivityFromRecents($taskId) failed: ${e.summary()}")
                        false
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
                return method.invoke(proxy, taskId) as? Boolean ?: false
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
