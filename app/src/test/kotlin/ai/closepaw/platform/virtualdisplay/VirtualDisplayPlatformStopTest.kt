package ai.closepaw.platform.virtualdisplay

import android.accessibilityservice.AccessibilityService
import android.media.ImageReader
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.taskhelper.TaskHelperWire
import ai.closepaw.trace.NoopTraceRecorder
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
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

        verify(exactly = 0) { shizuku.removeRootTasksOnDisplay(any(), any()) }
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

        verify(exactly = 0) { shizuku.removeRootTasksOnDisplay(any(), any()) }
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

    @Test
    fun `start replaces a running display before the watcher marks the dead helper Broken`() = runTest {
        assertStartReplacesDeadHelper(listOf(false))
    }

    @Test
    fun `start replaces a running display when its helper dies during the liveness checks`() = runTest {
        assertStartReplacesDeadHelper(listOf(true, false))
    }

    private suspend fun assertStartReplacesDeadHelper(liveness: List<Boolean>) {
        val oldReader = mockk<ImageReader>(relaxed = true)
        val newReader = mockk<ImageReader>(relaxed = true)
        val connection = mockk<TaskHelperSession.Connection>()
        every { connection.isAlive } returnsMany liveness
        every { connection.isDeathObserved() } returns true
        val owner = mockk<HelperVirtualDisplayPlatform>()
        every { owner.connection } returns connection
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        every { shizuku.createVirtualDisplay(any(), any(), any(), any(), any(), any(), any(), any()) } returns 113
        val platform = platform(service(), shizuku)
        setState(platform, VdState.Running(112, oldReader))
        setHelperDisplay(platform, owner)

        mockkStatic(SystemClock::class, ImageReader::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 0L
            every { ImageReader.newInstance(any(), any(), any(), any()) } returns newReader
            platform.start()

            assertThat(arbiter(platform).state).isEqualTo(VdState.Running(113, newReader))
            verify(exactly = 1) { oldReader.close() }
        } finally {
            unmockkStatic(SystemClock::class, ImageReader::class)
        }
    }

    @Test
    fun `unconfirmed surface switch preserves the running display and current surface mode`() = runTest {
        val displayId = 112
        val oldSurface = mockk<Surface>()
        every { oldSurface.isValid } returns true
        val reader = mockk<ImageReader>()
        every { reader.surface } returns oldSurface
        val state = VdState.Running(displayId, reader)
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service(), shizuku)
        setState(platform, state)

        val surface = mockk<Surface>()
        every { surface.isValid } returns true
        val owner = mockk<HelperVirtualDisplayPlatform>(relaxed = true)
        every { owner.displayId } returns displayId
        every { owner.setSurface(surface, shizuku) } throws IOException("Helper VD surface result unconfirmed")
        every { owner.restoreSurface(oldSurface) } returns true
        setHelperDisplay(platform, owner)

        val failure = assertThrows(VirtualDisplaySurfaceSwitchFailure::class.java) {
            platform.switchToLivePreview(surfaceView(surface))
        }
        assertThat(failure.previousSurfaceRestored).isTrue()

        assertThat(platform.getSurfaceMode()).isEqualTo(VirtualDisplaySurfaceMode.IMAGE_READER)
        assertThat(arbiter(platform).state).isEqualTo(state)
        verify(exactly = 1) { owner.restoreSurface(oldSurface) }
        verify(exactly = 0) { owner.release(any()) }
        verify(exactly = 0) { reader.close() }
        verify(exactly = 0) { owner.reassertSurface(any(), any()) }

        every { owner.setSurface(surface, shizuku) } returns true
        platform.switchToLivePreview(surfaceView(surface))
        assertThat(platform.getSurfaceMode()).isEqualTo(VirtualDisplaySurfaceMode.LIVE_PREVIEW)
    }

    @Test
    fun `unconfirmed helper restore reasserts the old surface and reports the outcome`() = runTest {
        val displayId = 112
        val oldSurface = mockk<Surface>()
        every { oldSurface.isValid } returns true
        val reader = mockk<ImageReader>()
        every { reader.surface } returns oldSurface
        val state = VdState.Running(displayId, reader)
        val shizuku = mockk<ShizukuClient>(relaxed = true)
        val platform = platform(service(), shizuku)
        setState(platform, state)

        val surface = mockk<Surface>()
        every { surface.isValid } returns true
        val token = mockk<IBinder>()
        val connection = mockk<TaskHelperSession.Connection>()
        val created = mockk<Bundle>(relaxed = true)
        every { created.getBoolean(TaskHelperWire.SUCCESS) } returns true
        every { created.getInt(TaskHelperWire.DISPLAY_ID) } returns displayId
        every { created.getBinder(TaskHelperWire.DISPLAY_CALLBACK) } returns token
        every { connection.displayCommand(TaskHelperWire.CREATE_DISPLAY, -1, any()) } returns created
        val acknowledged = mockk<Bundle>()
        every { acknowledged.getBoolean(TaskHelperWire.SUCCESS) } returns true
        every { connection.displayCommand(TaskHelperWire.SET_DISPLAY_SURFACE, displayId, any()) } returnsMany
                listOf(null, null, null, null, acknowledged, acknowledged, null, acknowledged)
        every { shizuku.setHelperDisplaySurface(token, oldSurface) } returns true andThen false
        val owner = checkNotNull(HelperVirtualDisplayPlatform.create(
                connection, 1080, 2400, 440, oldSurface, 0, 0, 0,
        ))
        setHelperDisplay(platform, owner)
        val view = surfaceView(surface)

        val restored = assertThrows(VirtualDisplaySurfaceSwitchFailure::class.java) {
            platform.switchToLivePreview(view)
        }
        assertThat(restored.previousSurfaceRestored).isTrue()
        val unknown = assertThrows(VirtualDisplaySurfaceSwitchFailure::class.java) {
            platform.switchToLivePreview(view)
        }
        assertThat(unknown.previousSurfaceRestored).isFalse()
        verifyOrder {
            connection.displayCommand(TaskHelperWire.SET_DISPLAY_SURFACE, displayId, any())
            connection.displayCommand(TaskHelperWire.SET_DISPLAY_SURFACE, displayId, any())
            shizuku.setHelperDisplaySurface(token, oldSurface)
            connection.displayCommand(TaskHelperWire.SET_DISPLAY_SURFACE, displayId, any())
            connection.displayCommand(TaskHelperWire.SET_DISPLAY_SURFACE, displayId, any())
            shizuku.setHelperDisplaySurface(token, oldSurface)
        }
        assertThat(arbiter(platform).state).isEqualTo(state)
        verify(exactly = 0) { connection.displayCommand(TaskHelperWire.RELEASE_DISPLAY, any(), any()) }
        verify(exactly = 0) { shizuku.releaseHelperDisplay(any()) }
        verify(exactly = 0) { reader.close() }

        platform.switchToImageReader()
        verify(exactly = 5) { connection.displayCommand(TaskHelperWire.SET_DISPLAY_SURFACE, displayId, any()) }

        platform.switchToLivePreview(view)
        val returnFailure = assertThrows(VirtualDisplaySurfaceSwitchFailure::class.java) {
            platform.switchToImageReader()
        }
        assertThat(returnFailure.previousSurfaceRestored).isTrue()
        assertThat(platform.getSurfaceMode()).isEqualTo(VirtualDisplaySurfaceMode.LIVE_PREVIEW)
    }

    private fun setHelperDisplay(platform: VirtualDisplayPlatform, owner: HelperVirtualDisplayPlatform) {
        val field = VirtualDisplayPlatform::class.java.getDeclaredField("helperDisplay")
        field.isAccessible = true
        field.set(platform, owner)
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
