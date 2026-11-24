package ai.closepaw.app

import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import ai.closepaw.platform.virtualdisplay.VdState
import ai.closepaw.platform.virtualdisplay.VirtualDisplayPlatform
import ai.closepaw.platform.virtualdisplay.VirtualDisplaySurfaceSwitchFailure
import ai.closepaw.ui.overlay.model.CapsuleMode

internal class AgentServiceViewerBridge(
    private val logTag: String,
    private val overlayControllerProvider: () -> ServiceOverlayController?,
    private val platformProvider: () -> VirtualDisplayPlatform?,
    private val openViewerActivity: () -> Unit,
    private val finishViewerActivity: () -> Unit,
) {
    fun onViewerOpened() {
        // VD IME policy: FALLBACK while the viewer is open (takeover typing), HIDE otherwise.
        platformProvider()?.onViewerVisibilityChanged(open = true)
        val overlay = overlayControllerProvider()
        if (overlay == null) {
            Log.w(logTag, "onViewerOpened: overlay controller not initialized")
            return
        }
        overlay.onViewerOpened()
    }

    fun onViewerClosed() {
        overlayControllerProvider()?.onViewerClosed()
        platformProvider()?.onViewerVisibilityChanged(open = false)
        // A deferred display-0 restore is re-evaluated on close (never on open).
        platformProvider()?.onViewerClosedRecheckDeferral()
    }

    fun onMainAppVisible() {
        overlayControllerProvider()?.onMainAppVisible()
    }

    fun onMainAppHidden() {
        overlayControllerProvider()?.onMainAppHidden()
    }

    fun openViewer() {
        openViewerActivity()
    }

    fun finishViewer() {
        finishViewerActivity()
    }

    @Throws(VirtualDisplaySurfaceSwitchFailure::class)
    fun notifyViewerVisible(surfaceView: SurfaceView) {
        platformProvider()?.switchToLivePreview(surfaceView)
    }

    @Throws(VirtualDisplaySurfaceSwitchFailure::class)
    fun notifyViewerHidden() {
        platformProvider()?.switchToImageReader()
    }

    fun viewerTouchTarget(): VdState.Running? {
        if (overlayControllerProvider()?.stateHolder?.mode?.value !is CapsuleMode.Takeover) return null
        return platformProvider()?.viewerTouchTarget()
    }

    suspend fun onViewerTouch(
        target: VdState.Running,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        viewWidth: Int,
        viewHeight: Int
    ): Boolean {
        val currentMode = overlayControllerProvider()?.stateHolder?.mode?.value
        if (currentMode !is CapsuleMode.Takeover && action != MotionEvent.ACTION_CANCEL) return false
        val platform = platformProvider() ?: return false
        return platform.onViewerTouch(
            target = target,
            action = action,
            x = x,
            y = y,
            downTime = downTime,
            eventTime = eventTime,
            viewWidth = viewWidth,
            viewHeight = viewHeight
        )
    }
}
