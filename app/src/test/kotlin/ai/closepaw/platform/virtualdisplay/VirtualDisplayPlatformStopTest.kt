package ai.closepaw.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.media.ImageReader
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.trace.NoopTraceRecorder
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualDisplayPlatformStopTest {

    @Test
    fun `stop cleans VD tasks after surface reset and before release`() = runTest {
        val displayId = 112
        val imageReader = mockk<ImageReader>(relaxed = true)
        val order = mutableListOf<String>()
        val service = service { order += "keyboard" }
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service, shizuku)

        setState(platform, VdState.Running(displayId, imageReader))

        val liveSurface = mockk<Surface>(relaxed = true)
        every { liveSurface.isValid } returns true
        every { shizuku.setVirtualDisplaySurface(displayId, liveSurface) } returns true
        platform.switchToLivePreview(surfaceView(liveSurface))
        assertThat(platform.getSurfaceMode()).isEqualTo(VirtualDisplaySurfaceMode.LIVE_PREVIEW)

        every { shizuku.removeRootTasksOnDisplay(displayId) } answers {
            assertThat(platform.getSurfaceMode()).isEqualTo(VirtualDisplaySurfaceMode.IMAGE_READER)
            order += "cleanup"
            1
        }
        every { shizuku.releaseVirtualDisplay(displayId) } answers {
            order += "release"
            Unit
        }

        platform.stop()

        assertThat(order).containsExactly("keyboard", "cleanup", "release").inOrder()
        verifyOrder {
            shizuku.removeRootTasksOnDisplay(displayId)
            shizuku.releaseVirtualDisplay(displayId)
            imageReader.close()
            shizuku.clearCachedProxies()
        }
        verify(exactly = 0) { shizuku.launchOnDisplay(any(), any(), any()) }
        verify(exactly = 0) { shizuku.executeShellCommand(any()) }
    }

    @Test
    fun `stopKeepingTasks releases the display and leaves its tasks alone`() = runTest {
        val displayId = 112
        val imageReader = mockk<ImageReader>(relaxed = true)
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service(), shizuku)

        setState(platform, VdState.Running(displayId, imageReader))

        platform.stopKeepingTasks()

        verify(exactly = 0) { shizuku.removeRootTasksOnDisplay(any()) }
        verify(exactly = 1) { shizuku.releaseVirtualDisplay(displayId) }
        verify(exactly = 1) { imageReader.close() }
    }

    @Test
    fun `stopKeepingTasks leaves tasks alone in Broken state too`() = runTest {
        val displayId = 112
        val imageReader = mockk<ImageReader>(relaxed = true)
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service(), shizuku)

        setState(platform, VdState.Broken("binder died", displayId, imageReader))

        platform.stopKeepingTasks()

        verify(exactly = 0) { shizuku.removeRootTasksOnDisplay(any()) }
        verify(exactly = 1) { shizuku.releaseVirtualDisplay(displayId) }
    }

    @Test
    fun `stop is idempotent and cleans tasks only once`() = runTest {
        val displayId = 112
        val imageReader = mockk<ImageReader>(relaxed = true)
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service(), shizuku)
        every { shizuku.removeRootTasksOnDisplay(displayId) } returns 1

        setState(platform, VdState.Running(displayId, imageReader))

        platform.stop()
        platform.stop()

        verify(exactly = 1) { shizuku.removeRootTasksOnDisplay(displayId) }
        verify(exactly = 1) { shizuku.releaseVirtualDisplay(displayId) }
        verify(exactly = 1) { imageReader.close() }
    }

    @Test
    fun `stop cleans tasks in Broken state and release still runs when cleanup returns minus one`() =
            runTest {
                val displayId = 112
                val imageReader = mockk<ImageReader>(relaxed = true)
                val shizuku = mockk<ShizukuClient>(relaxed = true)
                val platform = platform(service(), shizuku)
                every { shizuku.removeRootTasksOnDisplay(displayId) } returns -1

                setState(platform, VdState.Broken("binder died", displayId, imageReader))

                platform.stop()

                verifyOrder {
                    shizuku.removeRootTasksOnDisplay(displayId)
                    shizuku.releaseVirtualDisplay(displayId)
                    imageReader.close()
                }
            }

    @Test
    fun `stop cleans tasks in Draining state before release`() = runTest {
        val displayId = 112
        val imageReader = mockk<ImageReader>(relaxed = true)
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service(), shizuku)
        every { shizuku.removeRootTasksOnDisplay(displayId) } returns 1

        setState(platform, VdState.Draining(displayId, imageReader))

        platform.stop()

        verifyOrder {
            shizuku.removeRootTasksOnDisplay(displayId)
            shizuku.releaseVirtualDisplay(displayId)
            imageReader.close()
        }
    }

    @Test
    fun `stop releases display when task cleanup throws`() = runTest {
        val displayId = 112
        val imageReader = mockk<ImageReader>(relaxed = true)
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service(), shizuku)
        every { shizuku.removeRootTasksOnDisplay(displayId) } throws
                IllegalStateException("transport exploded")

        setState(platform, VdState.Running(displayId, imageReader))

        platform.stop()

        verify {
            shizuku.releaseVirtualDisplay(displayId)
            imageReader.close()
            shizuku.clearCachedProxies()
        }
    }

    private fun platform(
            service: AccessibilityService,
            shizuku: ShizukuClient
    ): VirtualDisplayPlatform =
            VirtualDisplayPlatform(
                    service = service,
                    shizuku = shizuku,
                    config =
                            VirtualDisplayConfig(
                                    width = 1080,
                                    height = 2400,
                                    densityDpi = 440,
                                    density = 2.75f
                            ),
                    sessionConfig = SessionConfig(),
                    traceRecorder = NoopTraceRecorder
            )

    private fun service(onKeyboardControllerAccess: () -> Unit = {}): AccessibilityService {
        val keyboard = mockk<AccessibilityService.SoftKeyboardController>(relaxed = true)
        val service = mockk<AccessibilityService>(relaxed = true)
        every { service.softKeyboardController } answers {
            onKeyboardControllerAccess()
            keyboard
        }
        return service
    }

    private fun surfaceView(surface: Surface): SurfaceView {
        val holder = mockk<SurfaceHolder>(relaxed = true)
        every { holder.surface } returns surface
        val view = mockk<SurfaceView>(relaxed = true)
        every { view.holder } returns holder
        return view
    }

    private suspend fun setState(platform: VirtualDisplayPlatform, state: VdState) {
        val arbiter = arbiter(platform)
        arbiter.withLifecycleTransition {
            arbiter.transitionTo(state)
        }
    }

    private fun arbiter(platform: VirtualDisplayPlatform): VdLifecycleArbiter {
        val field = VirtualDisplayPlatform::class.java.getDeclaredField("arbiter")
        field.isAccessible = true
        return field.get(platform) as VdLifecycleArbiter
    }

}
