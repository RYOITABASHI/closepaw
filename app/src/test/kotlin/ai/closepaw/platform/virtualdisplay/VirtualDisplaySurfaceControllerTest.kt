package ai.closepaw.platform.virtualdisplay

import android.media.ImageReader
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class VirtualDisplaySurfaceControllerTest {

    private val shizuku = mockk<ShizukuClient>(relaxed = true)
    private val imageReader = mockk<ImageReader>(relaxed = true)

    private fun controller() =
        VirtualDisplaySurfaceController(
            setSurface = shizuku::setVirtualDisplaySurface,
            displayIdProvider = { 7 },
            imageReaderProvider = { imageReader }
        )

    @Test
    fun `switchToLivePreview transitions mode when shizuku succeeds`() {
        val surface = mockk<Surface>(relaxed = true)
        every { surface.isValid } returns true
        val holder = mockk<SurfaceHolder>(relaxed = true)
        every { holder.surface } returns surface
        val view = mockk<SurfaceView>(relaxed = true)
        every { view.holder } returns holder
        every { shizuku.setVirtualDisplaySurface(7, surface) } returns true

        val c = controller()
        assertThat(c.mode()).isEqualTo(VirtualDisplaySurfaceMode.IMAGE_READER)

        c.switchToLivePreview(view)

        assertThat(c.mode()).isEqualTo(VirtualDisplaySurfaceMode.LIVE_PREVIEW)
        assertThat(c.liveSurfaceView()).isSameInstanceAs(view)
        verify { shizuku.setVirtualDisplaySurface(7, surface) }
    }

    @Test
    fun `surface changes commit state only on success in either direction`() {
        val readerSurface = mockk<Surface>()
        val viewerSurface = mockk<Surface>()
        val holder = mockk<SurfaceHolder>()
        val view = mockk<SurfaceView>()
        every { imageReader.surface } returns readerSurface
        every { viewerSurface.isValid } returns true
        every { holder.surface } returns viewerSurface
        every { view.holder } returns holder
        every { shizuku.setVirtualDisplaySurface(7, viewerSurface) } returnsMany listOf(false, true)
        every { shizuku.setVirtualDisplaySurface(7, readerSurface) } returnsMany listOf(false, true)

        val controller = controller()
        controller.switchToLivePreview(view)
        assertThat(controller.mode()).isEqualTo(VirtualDisplaySurfaceMode.IMAGE_READER)
        controller.switchToLivePreview(view)
        assertThat(controller.mode()).isEqualTo(VirtualDisplaySurfaceMode.LIVE_PREVIEW)

        controller.switchToImageReader()
        assertThat(controller.liveSurfaceView()).isSameInstanceAs(view)
        controller.switchToImageReader()
        assertThat(controller.mode()).isEqualTo(VirtualDisplaySurfaceMode.IMAGE_READER)
        assertThat(controller.liveSurfaceView()).isNull()
    }

    @Test
    fun `switchToLivePreview stays on ImageReader when holder has null surface`() {
        val holder = mockk<SurfaceHolder>(relaxed = true)
        every { holder.surface } returns null
        val view = mockk<SurfaceView>(relaxed = true)
        every { view.holder } returns holder

        val c = controller()
        c.switchToLivePreview(view)

        assertThat(c.mode()).isEqualTo(VirtualDisplaySurfaceMode.IMAGE_READER)
        assertThat(c.liveSurfaceView()).isNull()
        verify(exactly = 0) { shizuku.setVirtualDisplaySurface(any(), any()) }
    }

    @Test
    fun `switchToLivePreview stays on ImageReader when surface is invalid`() {
        val surface = mockk<Surface>(relaxed = true)
        every { surface.isValid } returns false
        val holder = mockk<SurfaceHolder>(relaxed = true)
        every { holder.surface } returns surface
        val view = mockk<SurfaceView>(relaxed = true)
        every { view.holder } returns holder

        val c = controller()
        c.switchToLivePreview(view)

        assertThat(c.mode()).isEqualTo(VirtualDisplaySurfaceMode.IMAGE_READER)
        verify(exactly = 0) { shizuku.setVirtualDisplaySurface(any(), any()) }
    }
}
