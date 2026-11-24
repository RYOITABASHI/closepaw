package ai.closepaw.ui.viewer

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.closepaw.app.AgentService
import ai.closepaw.platform.virtualdisplay.VdState
import ai.closepaw.platform.virtualdisplay.VirtualDisplaySurfaceSwitchFailure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * VirtualDisplayViewerActivity — Full-screen live preview of the virtual display.
 *
 * Shows the VD output via a SurfaceView (GPU-direct, 60fps). The Smart Capsule
 * overlay (managed by CapsuleOverlayHost via ServiceOverlayController) renders
 * as a system overlay on top of this activity — providing all UI controls.
 *
 * This activity is a pure SurfaceView container with no built-in controls.
 * All interaction (stop, takeover, exit, minimize) is via the Smart Capsule overlay.
 *
 * Lifecycle:
 * - onStart: switch VD output to SurfaceView + notify service (show capsule, hide island)
 * - onStop: switch back to ImageReader + notify service (hide capsule, show island)
 */
class VirtualDisplayViewerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VDViewerActivity"
    }

    private var surfaceView: SurfaceView? = null
    private data class ViewerTouch(
        val target: VdState.Running,
        val action: Int,
        val x: Float,
        val y: Float,
        val downTime: Long,
        val eventTime: Long,
        val width: Int,
        val height: Int,
    )

    private val touches = Channel<ViewerTouch>(Channel.UNLIMITED)
    private var queuedMoves = 0
    private var lastTouch: ViewerTouch? = null

    private fun enqueueTouch(view: SurfaceView, event: MotionEvent): Boolean {
        val service = AgentService.instance ?: return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_MOVE && queuedMoves >= 32) return true
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        ) return true
        val target = if (action == MotionEvent.ACTION_DOWN) {
            service.viewerTouchTarget()
        } else {
            lastTouch?.target
        } ?: return true
        val touch = ViewerTouch(target, action, event.x, event.y, event.downTime, event.eventTime,
            view.width, view.height)
        if (touches.trySend(touch).isFailure) return false
        if (action == MotionEvent.ACTION_MOVE) queuedMoves++
        lastTouch = touch.takeIf { action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE }
        return true
    }

    private fun cancelTouch() {
        val touch = lastTouch ?: return
        lastTouch = null
        touches.trySend(touch.copy(action = MotionEvent.ACTION_CANCEL, eventTime = SystemClock.uptimeMillis()))
    }

    internal var lastSurfaceSwitchFailure: VirtualDisplaySurfaceSwitchFailure? = null
        private set

    private inline fun switchViewerSurface(switch: () -> Unit) {
        try {
            switch()
        } catch (failure: VirtualDisplaySurfaceSwitchFailure) {
            lastSurfaceSwitchFailure = failure
            Log.w(TAG, "Virtual display surface switch failed", failure)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch {
            for (touch in touches) {
                if (touch.action == MotionEvent.ACTION_MOVE) queuedMoves--
                AgentService.instance?.onViewerTouch(
                    touch.target, touch.action, touch.x, touch.y, touch.downTime, touch.eventTime,
                    touch.width, touch.height,
                )
            }
        }

        setContent {
            VirtualDisplayViewerScreen(
                onSurfaceReady = { sv ->
                    surfaceView = sv
                    switchViewerSurface { AgentService.instance?.notifyViewerVisible(sv) }
                },
                onSurfaceDestroyed = {
                    cancelTouch()
                    surfaceView = null
                    switchViewerSurface { AgentService.instance?.notifyViewerHidden() }
                },
                onSurfaceTouch = ::enqueueTouch,
            )
        }

        // Service requests viewer-finish when the agent reaches a quiescent idle state.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentService.instance?.viewerFinishSignal?.collect {
                    if (!isFinishing) {
                        Log.d(TAG, "Service requested viewer finish")
                        finish()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        surfaceView?.let { sv ->
            switchViewerSurface { AgentService.instance?.notifyViewerVisible(sv) }
        }
        // Notify service: show capsule overlay, hide island, set SCREEN_VIEWING context
        AgentService.instance?.onViewerOpened()
        // Race-proof: if the agent is already idle when the viewer opens, the SharedFlow
        // emit from onViewerOpened()→applyVisibility() may land before the lifecycle
        // collector subscribes (both happen this same tick). Poll synchronously so the
        // viewer never gets stranded on a quiet VD surface.
        if (AgentService.instance?.shouldFinishViewerNow() == true && !isFinishing) {
            Log.d(TAG, "Agent already idle at viewer open - finishing immediately")
            finish()
        }
    }

    override fun onStop() {
        cancelTouch()
        super.onStop()
        Log.d(TAG, "onStop")
        switchViewerSurface { AgentService.instance?.notifyViewerHidden() }
        // Notify service: hide capsule overlay, show island, set BACKGROUND context
        AgentService.instance?.onViewerClosed()
        if (!isChangingConfigurations) {
            // Keep viewer transient; returning to app should land on chat task, not a stale viewer task.
            finish()
        }
    }
}

// ── Compose Screen ──────────────────────────────────────────────

/**
 * Pure full-screen SurfaceView for VD live preview.
 * No built-in controls — Smart Capsule overlay provides all UI.
 */
@Composable
private fun VirtualDisplayViewerScreen(
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSurfaceTouch: (SurfaceView, MotionEvent) -> Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LivePreviewSurface(
            onSurfaceReady = onSurfaceReady,
            onSurfaceDestroyed = onSurfaceDestroyed,
            onSurfaceTouch = onSurfaceTouch,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── Components ──────────────────────────────────────────────────

@Composable
private fun LivePreviewSurface(
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSurfaceTouch: (SurfaceView, MotionEvent) -> Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                isClickable = true
                setOnTouchListener { _, event ->
                    if (event == null) return@setOnTouchListener false
                    onSurfaceTouch(this, event)
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.d("VDViewerSurface", "Surface created")
                        onSurfaceReady(this@apply)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.d("VDViewerSurface", "Surface destroyed")
                        onSurfaceDestroyed()
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        Log.d("VDViewerSurface", "Surface changed: ${width}x$height")
                    }
                })
            }
        },
        modifier = modifier
    )
}
