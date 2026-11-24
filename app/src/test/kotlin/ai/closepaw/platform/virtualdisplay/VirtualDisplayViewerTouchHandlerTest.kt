package ai.closepaw.platform.virtualdisplay

import android.view.Display
import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test
import kotlinx.coroutines.runBlocking

class VirtualDisplayViewerTouchHandlerTest {

    private val config = VirtualDisplayConfig(
            width = 1000,
            height = 2000,
            densityDpi = 420,
            density = 2.625f
    )

    private fun handler(
            injector: VirtualDisplayInputInjector,
            shell: suspend (Array<String>) -> Int,
            displayId: Int = 7
    ) = VirtualDisplayViewerTouchHandler(
            config = config,
            displayIdProvider = { displayId },
            inputInjector = injector,
            executeShellInput = shell,
            retireDisplay = { _, _ -> error("Unexpected display retirement") },
    )

    @Test
    fun `viewer coordinates are scaled and clamped to display bounds`() = runBlocking<Unit> {
        val injector = mockk<VirtualDisplayInputInjector>(relaxed = true)
        every { injector.supportsRawViewerInput() } returns true
        coEvery { injector.injectMotionAction(any(), any(), any(), any(), any()) } returns ViewerMotionResult(InputOutcome.CONFIRMED)
        val shell = mockk<suspend (Array<String>) -> Int>(relaxed = true)

        val xSlot = slot<Float>()
        val ySlot = slot<Float>()
        coEvery {
            injector.injectMotionAction(
                    action = any(),
                    x = capture(xSlot),
                    y = capture(ySlot),
                    downTime = any(),
                    eventTime = any()
            )
        } returns ViewerMotionResult(InputOutcome.CONFIRMED)

        // Viewer 500x1000; touch at (250, 500) maps to (500, 1000).
        handler(injector, shell).onViewerTouch(
                action = MotionEvent.ACTION_DOWN,
                x = 250f, y = 500f,
                downTime = 100L, eventTime = 100L,
                viewWidth = 500, viewHeight = 1000
        )
        assertThat(xSlot.captured).isEqualTo(500f)
        assertThat(ySlot.captured).isEqualTo(1000f)

        // Out-of-bounds input clamped to (width-1, height-1).
        handler(injector, shell).onViewerTouch(
                action = MotionEvent.ACTION_DOWN,
                x = 9999f, y = 9999f,
                downTime = 100L, eventTime = 100L,
                viewWidth = 500, viewHeight = 1000
        )
        assertThat(xSlot.captured).isEqualTo((config.width - 1).toFloat())
        assertThat(ySlot.captured).isEqualTo((config.height - 1).toFloat())
    }

    @Test
    fun `invalid display ID short-circuits without dispatching`() = runBlocking<Unit> {
        val injector = mockk<VirtualDisplayInputInjector>(relaxed = true)
        val shell = mockk<suspend (Array<String>) -> Int>(relaxed = true)

        val result = handler(injector, shell, displayId = Display.INVALID_DISPLAY)
                .onViewerTouch(
                        action = MotionEvent.ACTION_DOWN,
                        x = 10f, y = 20f,
                        downTime = 0L, eventTime = 0L,
                        viewWidth = 500, viewHeight = 1000
                )

        assertThat(result).isFalse()
        coVerify(exactly = 0) {
            injector.injectMotionAction(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { shell(any()) }
    }

    @Test
    fun `viewer cancels an accepted down when a later packet is unconfirmed`() = runBlocking<Unit> {
        val injector = mockk<VirtualDisplayInputInjector>()
        every { injector.supportsRawViewerInput() } returns true
        coEvery {
            injector.injectMotionAction(MotionEvent.ACTION_DOWN, any(), any(), any(), any())
        } returns ViewerMotionResult(InputOutcome.CONFIRMED)
        coEvery {
            injector.injectMotionAction(MotionEvent.ACTION_MOVE, any(), any(), any(), any())
        } returns ViewerMotionResult(InputOutcome.UNCONFIRMED)
        coEvery { injector.cancelViewerMotion(7, 100L, any(), any()) } returns true
        val viewer = handler(injector, { error("Unexpected shell input") })

        assertThat(viewer.onViewerTouch(MotionEvent.ACTION_DOWN, 100f, 200f, 100L, 100L, 500, 1000)).isTrue()
        assertThat(viewer.onViewerTouch(MotionEvent.ACTION_MOVE, 150f, 250f, 100L, 120L, 500, 1000)).isFalse()
        assertThat(viewer.onViewerTouch(MotionEvent.ACTION_UP, 150f, 250f, 100L, 140L, 500, 1000)).isFalse()
        coVerify(exactly = 1) { injector.cancelViewerMotion(7, 100L, 300f, 500f) }
        coVerify(exactly = 0) {
            injector.injectMotionAction(MotionEvent.ACTION_UP, any(), any(), any(), any())
        }
    }

    @Test
    fun `shell path DOWN-MOVE-UP without threshold breach issues tap`() = runBlocking<Unit> {
        val injector = mockk<VirtualDisplayInputInjector>(relaxed = true)
        every { injector.supportsRawViewerInput() } returns false
        coEvery { injector.channelConfirmed(any()) } returns true
        val shell = mockk<suspend (Array<String>) -> Int>(relaxed = true)
        val cmdSlot = slot<Array<String>>()
        coEvery { shell(capture(cmdSlot)) } returns 0

        val h = handler(injector, shell)
        val vw = 500; val vh = 1000
        assertThat(h.onViewerTouch(MotionEvent.ACTION_DOWN, 100f, 200f, 0L, 0L, vw, vh)).isTrue()
        assertThat(h.onViewerTouch(MotionEvent.ACTION_MOVE, 101f, 201f, 0L, 10L, vw, vh)).isTrue()
        assertThat(h.onViewerTouch(MotionEvent.ACTION_UP, 102f, 202f, 0L, 50L, vw, vh)).isTrue()

        // Expected scaled target at UP: (102/500)*1000=204, (202/1000)*2000=404
        assertThat(cmdSlot.captured.toList()).isEqualTo(
                listOf("input", "-d", "7", "tap", "204", "404")
        )
    }

    @Test
    fun `shell path DOWN-MOVE-UP past threshold issues swipe`() = runBlocking<Unit> {
        val injector = mockk<VirtualDisplayInputInjector>(relaxed = true)
        every { injector.supportsRawViewerInput() } returns false
        coEvery { injector.channelConfirmed(any()) } returns true
        val shell = mockk<suspend (Array<String>) -> Int>(relaxed = true)
        val cmdSlot = slot<Array<String>>()
        coEvery { shell(capture(cmdSlot)) } returns 0

        val h = handler(injector, shell)
        val vw = 500; val vh = 1000
        // DOWN at viewer (100,200) → scaled (200,400)
        h.onViewerTouch(MotionEvent.ACTION_DOWN, 100f, 200f, 0L, 0L, vw, vh)
        // MOVE far past 18px threshold (post-scaling dx = (150-100)/500*1000 = 100)
        h.onViewerTouch(MotionEvent.ACTION_MOVE, 150f, 250f, 0L, 20L, vw, vh)
        // UP at viewer (150,250) → scaled (300,500)
        h.onViewerTouch(MotionEvent.ACTION_UP, 150f, 250f, 0L, 100L, vw, vh)

        val cmd = cmdSlot.captured.toList()
        assertThat(cmd.take(4)).isEqualTo(listOf("input", "-d", "7", "swipe"))
        assertThat(cmd[4]).isEqualTo("200")
        assertThat(cmd[5]).isEqualTo("400")
        assertThat(cmd[6]).isEqualTo("300")
        assertThat(cmd[7]).isEqualTo("500")
    }

    @Test
    fun `ACTION_CANCEL resets state so next UP issues tap`() = runBlocking<Unit> {
        val injector = mockk<VirtualDisplayInputInjector>(relaxed = true)
        every { injector.supportsRawViewerInput() } returns false
        coEvery { injector.channelConfirmed(any()) } returns true
        val shell = mockk<suspend (Array<String>) -> Int>(relaxed = true)
        val cmdSlot = slot<Array<String>>()
        coEvery { shell(capture(cmdSlot)) } returns 0

        val h = handler(injector, shell)
        val vw = 500; val vh = 1000
        h.onViewerTouch(MotionEvent.ACTION_DOWN, 100f, 200f, 0L, 0L, vw, vh)
        h.onViewerTouch(MotionEvent.ACTION_MOVE, 200f, 300f, 0L, 10L, vw, vh) // past threshold
        h.onViewerTouch(MotionEvent.ACTION_CANCEL, 200f, 300f, 0L, 20L, vw, vh)

        // New sequence, small move → tap expected
        h.onViewerTouch(MotionEvent.ACTION_DOWN, 50f, 60f, 0L, 100L, vw, vh)
        h.onViewerTouch(MotionEvent.ACTION_UP, 50f, 60f, 0L, 150L, vw, vh)

        assertThat(cmdSlot.captured[3]).isEqualTo("tap")
    }
}
