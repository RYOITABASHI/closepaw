package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DraftChromeStageTest {

    private val chrome = 42
    private val maps = 7

    /** Tasks on the two displays, and every call in order. */
    private class FakeTasks(
        var main: MutableList<DraftRootTask>,
        var run: MutableSet<Int> = mutableSetOf(),
    ) : DraftTaskControl {
        val calls = mutableListOf<String>()
        var mainReadable = true
        var failMoveHere = emptySet<Int>()
        var failMoveBack = emptySet<Int>()
        var throwOnFront = false

        override suspend fun mainDisplayTasks(): List<DraftRootTask>? = if (mainReadable) main.toList() else null

        override suspend fun chromeTasksOnRunDisplay(): Set<Int>? = run.toSet()

        override suspend fun moveToRunDisplay(taskId: Int): Boolean {
            calls += "here:$taskId"
            if (taskId in failMoveHere) return false
            main.removeAll { it.id == taskId }
            run += taskId
            return true
        }

        override suspend fun moveToMainDisplay(taskId: Int): Boolean {
            calls += "back:$taskId"
            if (taskId in failMoveBack) return false
            run -= taskId
            main.add(0, DraftRootTask(taskId, true))
            return true
        }

        override suspend fun moveToFront(taskId: Int): Boolean {
            calls += "front:$taskId"
            if (throwOnFront) throw IOException("binder")
            val task = main.first { it.id == taskId }
            main.remove(task)
            main.add(0, task)
            return true
        }

        override suspend fun remove(taskId: Int): Boolean {
            calls += "remove:$taskId"
            run -= taskId
            return true
        }
    }

    private val logged = mutableListOf<String>()
    private fun stage(tasks: FakeTasks) = DraftChromeStage(tasks) { logged += it }

    @Test
    fun `Chrome in front means the run does not start and nothing moves`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(chrome, true), DraftRootTask(maps, false)))
        val stage = stage(tasks)

        assertThat(stage.prepare()).isEqualTo(DraftChromeStage.CHROME_IN_USE)
        stage.restore()
        assertThat(tasks.calls).isEmpty()
    }

    @Test
    fun `unreadable tasks mean the run does not start`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(maps, false))).apply { mainReadable = false }
        assertThat(stage(tasks).prepare()).isEqualTo(DraftChromeStage.CHROME_TASKS_UNREADABLE)
        assertThat(tasks.calls).isEmpty()
    }

    @Test
    fun `Chrome behind the owner's app moves to the run display and comes back behind it`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(maps, false), DraftRootTask(chrome, true)))
        val stage = stage(tasks)

        assertThat(stage.prepare()).isNull()
        assertThat(tasks.run).containsExactly(chrome)
        assertThat(tasks.main.map { it.id }).containsExactly(maps)

        stage.restore()
        assertThat(tasks.calls).containsExactly("here:$chrome", "back:$chrome", "front:$maps").inOrder()
        assertThat(tasks.main.first().id).isEqualTo(maps)
        assertThat(tasks.run).isEmpty()
    }

    @Test
    fun `what the owner has in front at the end is what goes back in front`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(maps, false), DraftRootTask(chrome, true)))
        val stage = stage(tasks)
        stage.prepare()
        tasks.main.add(0, DraftRootTask(99, false)) // The owner switched apps during the run.

        stage.restore()
        assertThat(tasks.calls.last()).isEqualTo("front:99")
    }

    @Test
    fun `if the owner took Chrome back meanwhile, it is left alone and nothing covers it`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(maps, false), DraftRootTask(chrome, true)))
        val stage = stage(tasks)
        stage.prepare()
        // The owner opened Chrome on the main display: its task left the run display.
        tasks.run -= chrome
        tasks.main.add(0, DraftRootTask(chrome, true))

        stage.restore()
        assertThat(tasks.calls).containsExactly("here:$chrome")
    }

    @Test
    fun `with no Chrome task before, the run's own Chrome task is removed and nothing is refronted`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(maps, false)))
        val stage = stage(tasks)
        assertThat(stage.prepare()).isNull()
        tasks.run += 500 // The run's launch created Chrome on the run display.

        stage.restore()
        assertThat(tasks.calls).containsExactly("remove:500")
    }

    @Test
    fun `a failed move stops the start and puts back what already moved`() = runTest {
        val tasks = FakeTasks(
            mutableListOf(DraftRootTask(maps, false), DraftRootTask(chrome, true), DraftRootTask(43, true)),
        ).apply { failMoveHere = setOf(43) }
        val stage = stage(tasks)

        assertThat(stage.prepare()).isEqualTo(DraftChromeStage.CHROME_MOVE_FAILED)
        stage.restore()
        assertThat(tasks.calls).containsExactly("here:$chrome", "here:43", "back:$chrome", "front:$maps").inOrder()
    }

    @Test
    fun `a task that will not move back is logged and nothing is refronted`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(maps, false), DraftRootTask(chrome, true)))
            .apply { failMoveBack = setOf(chrome) }
        val stage = stage(tasks)
        stage.prepare()

        stage.restore()
        assertThat(tasks.calls).containsExactly("here:$chrome", "back:$chrome").inOrder()
        assertThat(logged.last()).contains("stuck=[$chrome]")
    }

    @Test
    fun `a failing refront is logged, and restore runs only once`() = runTest {
        val tasks = FakeTasks(mutableListOf(DraftRootTask(maps, false), DraftRootTask(chrome, true)))
            .apply { throwOnFront = true }
        val stage = stage(tasks)
        stage.prepare()

        stage.restore()
        stage.restore()
        assertThat(tasks.calls).containsExactly("here:$chrome", "back:$chrome", "front:$maps").inOrder()
        assertThat(logged.last()).contains("ok=false")
    }

    // ── Exit order ──

    @Test
    fun `the run's tab is closed before Chrome goes back, even when the close fails`() = runTest {
        val order = mutableListOf<String>()
        finishDraftChrome(
            tabId = "t1",
            closeTab = { order += "close:$it"; throw IOException("gone") },
            restore = { order += "restore" },
            log = { order += it },
        )
        assertThat(order).containsExactly("close:t1", "draft tab failed-IOException", "restore").inOrder()
    }

    @Test
    fun `without a tab only the restore runs`() = runTest {
        val order = mutableListOf<String>()
        finishDraftChrome(tabId = null, closeTab = { order += "close" }, restore = { order += "restore" }, log = { order += it })
        assertThat(order).containsExactly("restore")
    }
}
