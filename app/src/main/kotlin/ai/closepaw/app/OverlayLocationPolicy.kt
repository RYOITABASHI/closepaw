package ai.closepaw.app

import android.view.Display
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode

internal enum class OverlayUserLocation {
    MAIN_APP,
    VD_VIEWER,
    OTHER_APP
}

internal enum class ShowPreference {
    CAPSULE,
    ISLAND
}

internal data class OverlayVisibilityDecision(
    val showCapsule: Boolean,
    val showIsland: Boolean,
    val showGlow: Boolean,
    val normalizedShowPreference: ShowPreference,
)

internal fun isActivityWindowClass(className: String?): Boolean {
    val name = className?.substringBefore('$') ?: return false
    if (name.startsWith("android.widget.") ||
        name.startsWith("android.view.") ||
        name.startsWith("android.app.") ||
        name.startsWith("android.inputmethodservice.") ||
        name.startsWith("androidx.") ||
        name.contains("inputmethod", ignoreCase = true)) return false
    return name.contains('.')
}

internal fun resolveUserLocation(
    appPackage: String,
    packageName: String?,
    className: String?,
    displayId: Int? = null,
): OverlayUserLocation? {
    if (!isActivityWindowClass(className)) return null
    if (packageName == null) return null
    // Ignore non-default display activity windows to prevent VD app windows from
    // flipping location while user stays in MainActivity on the real screen.
    if (displayId != null && displayId != Display.DEFAULT_DISPLAY) return null
    if (packageName != appPackage) return OverlayUserLocation.OTHER_APP

    val normalizedClassName = className?.substringBefore('$') ?: return null
    return if (normalizedClassName.contains("VirtualDisplayViewerActivity")) {
        OverlayUserLocation.VD_VIEWER
    } else {
        OverlayUserLocation.MAIN_APP
    }
}

internal fun resolveCapsuleContext(
    platformMode: PlatformMode,
    location: OverlayUserLocation,
): CapsuleContext =
    when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            if (location == OverlayUserLocation.MAIN_APP) CapsuleContext.MAIN_APP
            else CapsuleContext.SCREEN_VIEWING
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            when (location) {
                OverlayUserLocation.MAIN_APP -> CapsuleContext.MAIN_APP
                OverlayUserLocation.VD_VIEWER -> CapsuleContext.SCREEN_VIEWING
                OverlayUserLocation.OTHER_APP -> CapsuleContext.BACKGROUND
            }
        }
    }

internal fun shouldOpenAppWhenIslandTapped(
    hasActiveTask: Boolean,
    mode: CapsuleMode,
): Boolean = !hasActiveTask && mode !is CapsuleMode.Done && mode !is CapsuleMode.Error

/**
 * Compute the new userLocation when MainActivity.onStop fires.
 *
 * - MAIN_APP → OTHER_APP: catches the race where the new foreground app's window-state
 *   event arrived BEFORE onStop and was dropped by the `isMainAppResumed` guard. Without
 *   this fallback, userLocation stays stuck at MAIN_APP and overlays never appear on the
 *   new app.
 * - VD_VIEWER → VD_VIEWER (preserve): VirtualDisplayViewerActivity.onStart calls
 *   onViewerOpened() before MainActivity.onStop runs, so VD_VIEWER is authoritative.
 *   Clobbering it to OTHER_APP would lose the edge glow on the first viewer entry until
 *   a second user action re-triggered onViewerOpened.
 * - OTHER_APP → OTHER_APP (no-op).
 */
internal fun resolveLocationOnMainAppHidden(
    current: OverlayUserLocation,
): OverlayUserLocation = when (current) {
    OverlayUserLocation.MAIN_APP -> OverlayUserLocation.OTHER_APP
    OverlayUserLocation.VD_VIEWER -> OverlayUserLocation.VD_VIEWER
    OverlayUserLocation.OTHER_APP -> OverlayUserLocation.OTHER_APP
}

/**
 * Whether the VirtualDisplayViewerActivity should auto-finish so the user is returned to
 * MainActivity instead of being stranded on a frozen, non-interactive VD surface.
 *
 * Trigger: VD platform + user is in the viewer + no active task + capsule has settled to
 * [CapsuleMode.Hidden]. This is the post-task quiescent state — the agent isn't acting,
 * the VD content is whatever the last action left behind, and there's no overlay UI to
 * navigate away. Without an auto-finish the user has to press back blindly.
 *
 * NOT triggered on [CapsuleMode.Done] (capsule still showing the success message — let
 * the user read it) or [CapsuleMode.Error] (user must dismiss). Both transition to Hidden
 * eventually, which is when the finish fires.
 */
internal fun shouldFinishViewerOnIdle(
    platformMode: PlatformMode,
    location: OverlayUserLocation,
    mode: CapsuleMode,
    hasActiveTask: Boolean,
): Boolean = platformMode == PlatformMode.VIRTUAL_DISPLAY &&
    location == OverlayUserLocation.VD_VIEWER &&
    !hasActiveTask &&
    mode is CapsuleMode.Hidden

internal fun deriveOverlayVisibility(
    platformMode: PlatformMode,
    location: OverlayUserLocation,
    mode: CapsuleMode,
    hasActiveTask: Boolean,
    showPreference: ShowPreference,
): OverlayVisibilityDecision {
    val isActive = hasActiveTask || mode is CapsuleMode.Done || mode is CapsuleMode.Error
    val normalizedShowPreference = when {
        location == OverlayUserLocation.MAIN_APP || !isActive -> showPreference
        mode is CapsuleMode.WaitingForInput ||
            mode is CapsuleMode.WaitingForAction ||
            mode is CapsuleMode.WaitingForApproval ||
            mode is CapsuleMode.Error -> ShowPreference.CAPSULE
        else -> showPreference
    }

    return when (platformMode) {
        PlatformMode.ACCESSIBILITY -> {
            val isOverlayContext = location != OverlayUserLocation.MAIN_APP && isActive
            val showCapsule = isOverlayContext && normalizedShowPreference == ShowPreference.CAPSULE
            OverlayVisibilityDecision(
                showCapsule = showCapsule,
                showIsland = isOverlayContext && normalizedShowPreference == ShowPreference.ISLAND,
                showGlow = location != OverlayUserLocation.MAIN_APP && isActive,
                normalizedShowPreference = normalizedShowPreference,
            )
        }
        PlatformMode.VIRTUAL_DISPLAY -> {
            val needsUserAttention = mode is CapsuleMode.WaitingForApproval ||
                mode is CapsuleMode.WaitingForInput ||
                mode is CapsuleMode.WaitingForAction ||
                mode is CapsuleMode.Error

            if (!isActive) {
                OverlayVisibilityDecision(
                    showCapsule = false,
                    showIsland = false,
                    showGlow = false,
                    normalizedShowPreference = normalizedShowPreference,
                )
            } else if (location == OverlayUserLocation.MAIN_APP && !needsUserAttention) {
                // In VD mode, main app UI handles normal interaction — hide overlay
                OverlayVisibilityDecision(
                    showCapsule = false,
                    showIsland = false,
                    showGlow = false,
                    normalizedShowPreference = normalizedShowPreference,
                )
            } else {
                // Show overlay in VD_VIEWER, OTHER_APP, or MAIN_APP when user attention needed.
                // For needsUserAttention modes, always force capsule regardless of preference —
                // in MAIN_APP the default preference is ISLAND, which won't show without this.
                val forceCapsule = needsUserAttention
                OverlayVisibilityDecision(
                    showCapsule = forceCapsule || normalizedShowPreference == ShowPreference.CAPSULE,
                    showIsland = !forceCapsule && normalizedShowPreference == ShowPreference.ISLAND,
                    showGlow = (location == OverlayUserLocation.VD_VIEWER || needsUserAttention) && hasActiveTask,
                    normalizedShowPreference = normalizedShowPreference,
                )
            }
        }
    }
}

/**
 * Quiet mode for Shelly-handed background runs on the virtual display: the user asked another
 * app (ChatGPT via Shelly) and wants their screen left alone. While they are in some other app
 * (OTHER_APP) no capsule / island / glow is shown on the main display — the ongoing notification
 * is the status surface and its Stop / Allow / Reject actions are the only controls. The capsule
 * still appears for states the notification cannot answer (typed input, "done" confirmation),
 * and whenever the user deliberately opens ClosePaw or the VD viewer. Terminal states (Done,
 * Error) stay hidden too: the run's outcome reaches the user through the result callback and the
 * notification, so the quiet flag must outlive the run and only be cleared when a user-started
 * session takes over (never at the moment the run finishes, or the Done/Error capsule would pop).
 *
 * Apart from the user's wish this also removes a large touchable accessibility overlay (with a
 * Stop button) from the screen the agent is NOT operating, so no stray touch can end the run.
 */
internal fun applyQuietBackgroundRun(
    decision: OverlayVisibilityDecision,
    quiet: Boolean,
    platformMode: PlatformMode,
    location: OverlayUserLocation,
    mode: CapsuleMode,
): OverlayVisibilityDecision {
    if (!quiet) return decision
    if (platformMode != PlatformMode.VIRTUAL_DISPLAY) return decision
    if (location != OverlayUserLocation.OTHER_APP) return decision
    val needsCapsule = mode is CapsuleMode.WaitingForInput ||
        mode is CapsuleMode.WaitingForAction
    if (needsCapsule) return decision
    return decision.copy(showCapsule = false, showIsland = false, showGlow = false)
}

/**
 * Whether user touch interaction with the underlying screen should be blocked.
 *
 * - A11y OTHER_APP: block while agent owns control (non-terminal, non-takeover modes).
 * - VD_VIEWER: block until takeover is confirmed.
 */
internal fun shouldLockUserInteraction(
    platformMode: PlatformMode,
    location: OverlayUserLocation,
    mode: CapsuleMode,
): Boolean {
    val userOwnsControl = mode is CapsuleMode.Takeover
    val nonInteractiveState = mode is CapsuleMode.Hidden ||
        mode is CapsuleMode.Done ||
        mode is CapsuleMode.Error
    if (userOwnsControl || nonInteractiveState) return false

    return when (platformMode) {
        PlatformMode.ACCESSIBILITY -> location == OverlayUserLocation.OTHER_APP
        PlatformMode.VIRTUAL_DISPLAY -> location == OverlayUserLocation.VD_VIEWER
    }
}

/**
 * Whether the capsule overlay window should be touchable (i.e. NOT have FLAG_NOT_TOUCHABLE).
 *
 * Only [CapsuleMode.Hidden] passes touches through — all other modes need user interaction
 * (buttons, input fields, or the full-screen touch shield during Running).
 */
internal fun shouldCapsuleOverlayBeTouchable(mode: CapsuleMode): Boolean =
    mode !is CapsuleMode.Hidden
