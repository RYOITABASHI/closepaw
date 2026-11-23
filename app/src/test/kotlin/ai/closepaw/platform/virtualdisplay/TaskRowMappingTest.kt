package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperProtocol
import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskRowMappingTest {
    @Test fun mapsIncumbentFieldsInWireOrder() {
        val packages = setOf("org.top", "org.base", "org.real", "org.orig", "org.intent")
        val rows = listOf(
            TaskHelperRow(9, "org.top", "org.base", packages, true, true),
            TaskHelperRow(2, null, "org.base", setOf("org.base"), false, false),
            TaskHelperRow(4, null, null, emptySet(), null, false),
        )
        assertEquals(listOf(DisplayTask(9, "org.top", true), DisplayTask(2, "org.base"), DisplayTask(4, null)),
            TaskRowMapping.toDisplayTasks(rows))
        assertEquals(listOf(
            ShizukuActivityTaskTransport.RootTask(9, packages, true),
            ShizukuActivityTaskTransport.RootTask(2, setOf("org.base"), false),
            ShizukuActivityTaskTransport.RootTask(4, emptySet(), null),
        ), TaskRowMapping.toRootTasks(rows))
        assertEquals(listOf(9, 2), TaskRowMapping.toTaskIds(rows, "org.base").toList())
        assertEquals(setOf(9), TaskRowMapping.toTaskIds(rows, "org.intent"))
    }

    @Test fun matchesIncumbentReflectiveMappingForAllFixtureClasses() {
        val roots = listOf(PinnedRoot(), EmptyRoot(), TopAbsentRoot(), MultiPackageRoot())
        val wire = TaskHelperProtocol.encodeRootTaskRows(listOf(
            TaskHelperProtocol.encodeRootTaskRow(9, null, null, emptyList(), true, true),
            TaskHelperProtocol.encodeRootTaskRow(2, null, null, emptyList(), null, false),
            TaskHelperProtocol.encodeRootTaskRow(5, null, "org.base", listOf("org.base"), false, false),
            TaskHelperProtocol.encodeRootTaskRow(
                7, "org.top", "org.base",
                listOf("org.top", "org.base", "org.real", "org.orig", "org.intent"),
                true, false,
            ),
        ))
        val rows = checkNotNull(TaskHelperRows.parse(wire))
        val transport = ShizukuActivityTaskTransport { Proxy(roots) }
        assertEquals(transport.rootTasksOnDisplay(0), TaskRowMapping.toDisplayTasks(rows))
        assertEquals(transport.rootTasksWithPackagesOnDisplay(0), TaskRowMapping.toRootTasks(rows))
        assertEquals(transport.rootTaskIdsOnDisplay(0, "org.base"), TaskRowMapping.toTaskIds(rows, "org.base"))
    }

    class Proxy(private val roots: List<Any>) {
        fun getAllRootTaskInfosOnDisplay(displayId: Int): List<Any> = roots
    }

    class EmptyRoot {
        @JvmField val taskId = 2
    }

    class PinnedRoot {
        @JvmField val taskId = 9
        @JvmField val visible = true
        @JvmField val configuration = Configuration()
    }

    class TopAbsentRoot {
        @JvmField val taskId = 5
        @JvmField val topActivity: ComponentName? = null
        @JvmField val baseActivity = ComponentName("org.base", "org.base.Main")
        @JvmField val visible = false
    }

    class MultiPackageRoot {
        @JvmField val taskId = 7
        @JvmField val topActivity = ComponentName("org.top", "org.top.Main")
        @JvmField val baseActivity = ComponentName("org.base", "org.base.Main")
        @JvmField val realActivity = ComponentName("org.real", "org.real.Main")
        @JvmField val origActivity = ComponentName("org.orig", "org.orig.Main")
        @JvmField val baseIntent = Intent().setComponent(ComponentName("org.intent", "org.intent.Main"))
        @JvmField val visible = true
    }

    class Configuration {
        @JvmField val windowConfiguration = WindowConfiguration()
    }

    class WindowConfiguration {
        fun getWindowingMode(): Int = 2
    }
}
