package ai.closepaw.platform.virtualdisplay

import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.platform.ActionResult
import android.os.SystemClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class VirtualDisplayAppControllerChromeTest {
    private val displayId = 42
    private val chromePackage = ChromeFlagDeepLink.CHROME_PACKAGE
    private val chromeTask = DisplayTask(7, chromePackage)
    private val otherTask = DisplayTask(8, "com.example.other")
    private val shizuku = mockk<ShizukuClient>()
    private val controller = VirtualDisplayAppController(mockk(), shizuku, { displayId })
    private val elapsedMs = AtomicLong()
    private val component = "$chromePackage/${ChromeFlagDeepLink.CHROME_MAIN_ACTIVITY}"

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsedMs.getAndAdd(750) }
        every { shizuku.executeQuietShellCommand(any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun showChromeSucceedsWhenChromeIsObservedInFront() = runBlocking {
        every { shizuku.rootTasksOnDisplay(displayId) } returns listOf(chromeTask, otherTask)

        assertEquals(ActionResult.Success("Opened Chrome on display $displayId"), controller.showChrome())
        verify(exactly = 1) {
            shizuku.executeQuietShellCommand(arrayOf("am", "start", "-n", component, "--display", "$displayId"))
            shizuku.rootTasksOnDisplay(displayId)
        }
    }

    @Test
    fun openUrlFailsWhenChromeNeverAppears() = runBlocking {
        val url = "https://example.com/?q=a&b=c"
        every { shizuku.rootTasksOnDisplay(displayId) } returns listOf(otherTask)

        assertEquals(
            ActionResult.Failure("Launch delivered but $chromePackage never appeared on display $displayId"),
            controller.openUrlInChrome(url),
        )
        verify(exactly = 1) {
            shizuku.executeQuietShellCommand(
                arrayOf("am", "start", "-a", "android.intent.action.VIEW", "-d", url, "-n", component, "--display", "$displayId"),
            )
        }
    }

    @Test
    fun showChromeFailsWhenChromeStaysBehindAnotherTask() = runBlocking {
        every { shizuku.rootTasksOnDisplay(displayId) } returns listOf(otherTask, chromeTask)
        every { shizuku.moveTaskToFront(chromeTask.taskId) } returns true

        assertEquals(
            ActionResult.Failure("Launch delivered but $chromePackage did not come to the front on display $displayId"),
            controller.showChrome(),
        )
        verify(exactly = 1) { shizuku.moveTaskToFront(chromeTask.taskId) }
    }

    @Test
    fun showChromeFailsClosedWhenTasksCannotBeRead() = runBlocking {
        every { shizuku.rootTasksOnDisplay(displayId) } returns null

        assertEquals(unverifiable(), controller.showChrome())
    }

    @Test
    fun showChromeRejectsAnObservationCompletedAtTheDeadline() = runBlocking {
        every { shizuku.rootTasksOnDisplay(displayId) } answers {
            elapsedMs.set(3_000)
            listOf(chromeTask)
        }

        assertEquals(unverifiable(), controller.showChrome())
    }

    @Test
    fun shellFailureSkipsTaskObservation() = runBlocking {
        every { shizuku.executeQuietShellCommand(any()) } returns 1

        assertEquals(ActionResult.Failure("am start failed with code 1"), controller.showChrome())
        verify(exactly = 0) { shizuku.rootTasksOnDisplay(any()) }
    }

    private fun unverifiable() = ActionResult.Failure(
        "Launch delivered but surfacing of $chromePackage on display $displayId could not be verified",
    )
}
