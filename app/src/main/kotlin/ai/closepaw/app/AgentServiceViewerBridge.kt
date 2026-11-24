package ai.closepaw.app

import android.util.Log
import android.view.SurfaceView
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

    fun onViewerTouch(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long,
        viewWidth: Int,
        viewHeight: Int
    ): Boolean {
        val currentMode = overlayControllerProvider()?.stateHolder?.mode?.value
        if (currentMode !is CapsuleMode.Takeover) return true
        val platform = platformProvider() ?: return false
        return platform.onViewerTouch(
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
