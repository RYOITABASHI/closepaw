package ai.closepaw.platform.virtualdisplay

import android.media.ImageReader
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import java.io.IOException

enum class VirtualDisplaySurfaceMode {
        IMAGE_READER,
        LIVE_PREVIEW
}

/** An unconfirmed switch leaves the active surface unknown unless recovery was confirmed. */
class VirtualDisplaySurfaceSwitchFailure(
        displayId: Int,
        val previousSurfaceRestored: Boolean,
        cause: IOException,
) : IOException(
        if (previousSurfaceRestored) "Surface switch unconfirmed for display $displayId. Previous surface restored."
        else "Surface switch unconfirmed for display $displayId. Active surface is unknown.",
        cause,
)

/**
 * Manages which surface the virtual display renders to.
 *
 * Switches between ImageReader (headless capture) and SurfaceView (live preview).
 */
internal class VirtualDisplaySurfaceController(
        private val setSurface: (Int, Surface) -> Boolean,
        private val displayIdProvider: () -> Int,
        private val imageReaderProvider: () -> ImageReader?
) {
        companion object {
                private const val TAG = "VDSurfaceController"
        }

        private data class SurfaceState(
                val mode: VirtualDisplaySurfaceMode,
                val liveSurfaceView: SurfaceView?,
                val liveSurface: Surface? = null
        )

        private val stateLock = Any()
        @Volatile
        private var state = SurfaceState(mode = VirtualDisplaySurfaceMode.IMAGE_READER, liveSurfaceView = null)

        // Retain the last surface as a recovery target, even when its attachment is unknown.
        private var unknownSurfaceFailure: VirtualDisplaySurfaceSwitchFailure? = null

        fun mode(): VirtualDisplaySurfaceMode = state.mode

        fun liveSurfaceView(): SurfaceView? = state.liveSurfaceView

        fun currentSurface(): Surface? = state.liveSurface ?: imageReaderProvider()?.surface

        fun reset() {
                synchronized(stateLock) {
                        unknownSurfaceFailure = null
                        state =
                                SurfaceState(
                                        mode = VirtualDisplaySurfaceMode.IMAGE_READER,
                                        liveSurfaceView = null
                                )
                }
        }

        fun switchToLivePreview(surfaceView: SurfaceView) {
                synchronized(stateLock) {
                        // Allow surface replacement even when already in LIVE_PREVIEW
                        // (e.g., viewer recreated after config change or surface loss)
                        if (state.mode == VirtualDisplaySurfaceMode.LIVE_PREVIEW &&
                                state.liveSurfaceView === surfaceView && unknownSurfaceFailure == null) return
                        val surface =
                                surfaceView.holder.surface
                                        ?: run {
                                                Log.w(
                                                        TAG,
                                                        "SurfaceView holder has no valid surface, staying on ImageReader"
                                                )
                                                unknownSurfaceFailure?.let { throw it }
                                                return
                                        }
                        if (!surface.isValid) {
                                Log.w(TAG, "SurfaceView surface is invalid, staying on ImageReader")
                                unknownSurfaceFailure?.let { throw it }
                                return
                        }

                        val ok = applySurface(surface)
                        if (ok) {
                                state =
                                        SurfaceState(
                                                mode = VirtualDisplaySurfaceMode.LIVE_PREVIEW,
                                                liveSurfaceView = surfaceView,
                                                liveSurface = surface
                                        )
                                Log.i(TAG, "Switched to live preview surface")
                        } else {
                                Log.w(TAG, "setSurface failed, keeping the current surface")
                        }
                }
        }

        fun switchToImageReader() {
                synchronized(stateLock) {
                        if (state.mode == VirtualDisplaySurfaceMode.IMAGE_READER && unknownSurfaceFailure == null) return
                        val reader = imageReaderProvider() ?: run {
                                unknownSurfaceFailure?.let { throw it }
                                return
                        }
                        val ok = applySurface(reader.surface)
                        if (ok) {
                                state =
                                        SurfaceState(
                                                mode = VirtualDisplaySurfaceMode.IMAGE_READER,
                                                liveSurfaceView = null
                                        )
                                Log.i(TAG, "Switched to ImageReader surface")
                        } else {
                                Log.w(
                                        TAG,
                                        "Failed to switch back to ImageReader — display may be in bad state"
                                )
                        }
                }
        }

        private fun applySurface(surface: Surface): Boolean {
                val applied = try {
                        setSurface(displayIdProvider(), surface)
                } catch (failure: VirtualDisplaySurfaceSwitchFailure) {
                        unknownSurfaceFailure = failure.takeUnless { it.previousSurfaceRestored }
                        throw failure
                }
                if (applied) unknownSurfaceFailure = null
                else unknownSurfaceFailure?.let { throw it }
                return applied
        }
}
