package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShizukuActivityTaskTransportTest {

    @Test
    fun `startActivity rejects an overload that only matches the old shape`() {
        val transport = ShizukuActivityTaskTransport { WrongStartActivitySignature() }
        assertThat(transport.startActivity(android.content.Intent(), null)).isFalse()
    }

    class WrongStartActivitySignature {
        @Suppress("UNUSED_PARAMETER")
        fun startActivityAsUser(
                caller: Any?, callingPackage: String, feature: String?, intent: android.content.Intent,
                type: String?, resultTo: android.os.IBinder?, resultWho: String?, request: Int,
                flags: Int, profiler: Any?, options: android.os.Bundle?, user: Int,
        ): Int = 0
    }


    @Test
    fun `removes only root task ids returned for display`() {
        val displayId = 112
        val rootInfo =
                RootTaskInfo(
                        taskId = 41,
                        childTaskIds = intArrayOf(501, 502)
                )
        val proxy =
                FakeActivityTaskManagerProxy(
                        infosByDisplay = mapOf(displayId to listOf(rootInfo))
                )
        val transport = ShizukuActivityTaskTransport { proxy }

        val removed = transport.removeRootTasksOnDisplay(displayId)

        assertThat(removed).isEqualTo(1)
        assertThat(proxy.requestedDisplays).containsExactly(displayId)
        assertThat(proxy.removedTaskIds).containsExactly(41)
    }

    @Test
    fun `returns minus one when proxy cannot be resolved`() {
        val transport =
                ShizukuActivityTaskTransport {
                    error("activity_task unavailable")
                }

        val removed = transport.removeRootTasksOnDisplay(112)

        assertThat(removed).isEqualTo(-1)
    }

    @Test
    fun `returns minus one for invalid display without resolving proxy`() {
        var proxyResolved = false
        val transport =
                ShizukuActivityTaskTransport {
                    proxyResolved = true
                    FakeActivityTaskManagerProxy(emptyMap())
                }

        val removed = transport.removeRootTasksOnDisplay(-1)

        assertThat(removed).isEqualTo(-1)
        assertThat(proxyResolved).isFalse()
    }

    @Test
    fun `returns zero when display has no root tasks`() {
        val displayId = 112
        val proxy = FakeActivityTaskManagerProxy(infosByDisplay = emptyMap())
        val transport = ShizukuActivityTaskTransport { proxy }

        val removed = transport.removeRootTasksOnDisplay(displayId)

        assertThat(removed).isEqualTo(0)
        assertThat(proxy.requestedDisplays).containsExactly(displayId)
        assertThat(proxy.removedTaskIds).isEmpty()
    }

    @Test
    fun `counts only tasks successfully removed`() {
        val displayId = 112
        val first = RootTaskInfo(taskId = 41)
        val second = RootTaskInfo(taskId = 42)
        val proxy =
                FakeActivityTaskManagerProxy(
                        infosByDisplay = mapOf(displayId to listOf(first, second)),
                        removeResults = mapOf(42 to false)
                )
        val transport = ShizukuActivityTaskTransport { proxy }

        val removed = transport.removeRootTasksOnDisplay(displayId)

        assertThat(removed).isEqualTo(1)
        assertThat(proxy.removedTaskIds).containsExactly(41, 42).inOrder()
    }

    @Test
    fun `returns minus one when root task query returns unexpected type`() {
        val transport = ShizukuActivityTaskTransport { BadActivityTaskManagerProxy() }

        val removed = transport.removeRootTasksOnDisplay(112)

        assertThat(removed).isEqualTo(-1)
    }

    open class TaskInfo(
            @JvmField val taskId: Int
    )

    class RootTaskInfo(
            taskId: Int,
            @JvmField val childTaskIds: IntArray = intArrayOf()
    ) : TaskInfo(taskId)

    class FakeActivityTaskManagerProxy(
            private val infosByDisplay: Map<Int, List<Any>>,
            private val removeResults: Map<Int, Boolean> = emptyMap()
    ) {
        val requestedDisplays = mutableListOf<Int>()
        val removedTaskIds = mutableListOf<Int>()

        fun getAllRootTaskInfosOnDisplay(displayId: Int): List<Any> {
            requestedDisplays += displayId
            return infosByDisplay[displayId] ?: emptyList()
        }

        fun removeTask(taskId: Int): Boolean {
            removedTaskIds += taskId
            return removeResults[taskId] ?: true
        }
    }

    class BadActivityTaskManagerProxy {
        fun getAllRootTaskInfosOnDisplay(displayId: Int): Any = "not-a-list-$displayId"

        fun removeTask(taskId: Int): Boolean = error("should not remove task $taskId")
    }
}
