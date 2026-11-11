package ai.closepaw.platform.virtualdisplay

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
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

                /** Calls through Shizuku run as the shell user, whose package this is. */
                private const val SHELL_PACKAGE = "com.android.shell"
        }

        fun removeRootTasksOnDisplay(displayId: Int): Int {
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
                                if (removeTask(proxy, taskId)) removed++
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
         * Ids of the root tasks on [displayId] whose base, top or real activity belongs to
         * [packageName]; null when the transport fails.
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

        /** A root task: its id, the packages of its activities, and whether it is visible. */
        data class RootTask(val id: Int, val packages: Set<String>, val visible: Boolean?)

        /**
         * Root tasks on [displayId] in the order the system lists them, which is from the one in
         * front to the back; null when the transport fails.
         */
        fun rootTasksOnDisplay(displayId: Int): List<RootTask>? {
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

        /**
         * IActivityTaskManager.moveRootTaskToDisplay (moveStackToDisplay before Android 12): the
         * task and its windows move to [displayId], on top there.
         */
        fun moveRootTaskToDisplay(taskId: Int, displayId: Int): Boolean {
                return try {
                        val proxy = activityTaskManagerProxy()
                        val int = Int::class.javaPrimitiveType
                        val method =
                                runCatching { proxy.javaClass.getMethod("moveRootTaskToDisplay", int, int) }
                                        .getOrNull()
                                        ?: proxy.javaClass.getMethod("moveStackToDisplay", int, int)
                        method.invoke(proxy, taskId, displayId)
                        Log.i(TAG, "moved task $taskId to display $displayId")
                        true
                } catch (e: Exception) {
                        Log.w(TAG, "move task failed: taskId=$taskId displayId=$displayId err=${e.summary()}")
                        false
                }
        }

        /**
         * Puts a task in front on its display: IActivityTaskManager.moveTaskToFront as the shell,
         * or startActivityFromRecents where that signature is not there.
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
                return try {
                        proxy.javaClass
                                .getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java)
                                .invoke(proxy, taskId, null)
                        Log.i(TAG, "task $taskId to front from recents")
                        true
                } catch (e: Exception) {
                        Log.w(TAG, "startActivityFromRecents failed: taskId=$taskId err=${e.summary()}")
                        false
                }
        }

        /** Removes one task by id. False when it was not removed or the transport failed. */
        fun removeRootTask(taskId: Int): Boolean {
                return try {
                        removeTask(activityTaskManagerProxy(), taskId)
                } catch (e: Exception) {
                        Log.w(TAG, "remove task failed: taskId=$taskId err=${e.summary()}")
                        false
                }
        }

        private fun taskPackages(info: Any): Set<String> {
                val packages = mutableSetOf<String>()
                for (name in listOf("baseActivity", "topActivity", "realActivity", "origActivity")) {
                        val component =
                                runCatching { info.javaClass.getField(name).get(info) }.getOrNull()
                        (component as? ComponentName)?.packageName?.let(packages::add)
                }
                val intent = runCatching { info.javaClass.getField("baseIntent").get(info) }.getOrNull()
                (intent as? Intent)?.component?.packageName?.let(packages::add)
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
