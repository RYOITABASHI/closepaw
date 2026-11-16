package ai.closepaw.platform

import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.protocol.PlatformMode

/**
 * AndroidPlatform - Abstraction for Android-specific operations.
 * 
 * This interface allows the orchestration logic to be tested without
 * requiring an actual Android device or accessibility service.
 * 
 * Implementations:
 * - AccessibilityPlatform: Real implementation using AccessibilityService
 * - MockPlatform: Test implementation with predefined responses
 */
interface AndroidPlatform {

    /**
     * Which platform implementation this is. AccessibilityPlatform vs.
     * VirtualDisplayPlatform must be distinguishable so the UI can render the
     * effective mode of the live session (which may diverge from the persisted
     * intent when PlatformFactory falls back).
     */
    val mode: PlatformMode

    /**
     * Initialize platform resources.
     *
     * Called once before the first captureScreen/performAction.
     * AccessibilityPlatform: no-op (already ready when service is connected).
     * VirtualDisplayPlatform: creates the virtual display + ImageReader.
     */
    suspend fun start() {}

    /**
     * Release platform resources.
     *
     * Called during session cleanup. Must be idempotent.
     * AccessibilityPlatform: no-op.
     * VirtualDisplayPlatform: releases virtual display + ImageReader.
     */
    suspend fun stop() {}

    /**
     * Work the platform must still do while the session idles, so Hot Idle teardown waits for it
     * (polled by the idle timeout). VirtualDisplayPlatform: a deferred display-0 task return
     * that executes on screen off. Default: none.
     */
    fun hasDeferredWork(): Boolean = false

    /**
     * The session's current task ended (any outcome; its TaskCompleted already emitted), called
     * BEFORE the session goes Idle, so run-end work recorded here is ordered before any follow-up
     * [start]. Must not block or throw. VirtualDisplayPlatform: records the Shelly run-end
     * display-0 task return.
     */
    fun onTaskEnded() {}

    /**
     * Capture the current screen state.
     * 
     * @return ScreenSnapshot containing UI elements and their properties
     */
    suspend fun captureScreen(): ScreenSnapshot
    
    /**
     * Perform an atomic UI action on the device.
     *
     * Each UIAction variant maps to exactly one Android API call.
     * No fallback, no target resolution, no UI change detection.
     *
     * @param action The atomic action to perform
     * @return ActionResult indicating success or failure
     */
    suspend fun performAction(action: UIAction): ActionResult
    
    /**
     * Check if the platform has all required permissions.
     * 
     * @return true if all permissions are available
     */
    fun hasRequiredPermissions(): Boolean
    
    /**
     * Get the current package name of the foreground app.
     * 
     * @return Package name or null if unavailable
     */
    fun getCurrentPackageName(): String?
    
    /**
     * Get display metrics.
     * 
     * @return DisplayInfo containing screen dimensions
     */
    fun getDisplayInfo(): DisplayInfo
    
    // =========================================================================
    // Platform Capabilities
    // =========================================================================

    /**
     * Whether tap-to-focus fallback is safe for text input.
     *
     * VD mode returns false: tapping to focus triggers IME on the wrong display.
     * A11y mode returns true: existing behavior, tap-to-focus works normally.
     */
    fun allowTapToFocus(): Boolean = true

    /**
     * Show visual feedback for a verified native scroll action.
     *
     * This is intentionally separate from [performAction]: scroll orchestration owns fallback and
     * post-action verification, so it decides when a native scroll attempt should become visible.
     */
    fun showScrollVisualization(x: Int, y: Int, direction: String) {}

    // =========================================================================
    // App Management (P0)
    // =========================================================================
    
    /**
     * Get list of installed launchable apps.
     * 
     * @return List of AppInfo for apps with launcher activities
     */
    suspend fun getInstalledApps(): List<AppInfo>
    
    /**
     * Launch an app by package name.
     * 
     * @param packageName The package name of the app to launch
     * @return ActionResult indicating success or failure
     */
    suspend fun launchApp(packageName: String): ActionResult
}

/**
 * DisplayInfo - Information about the device display.
 */
data class DisplayInfo(
    val widthPixels: Int,
    val heightPixels: Int,
    val density: Float
)

/**
 * AppInfo - Information about an installed app.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean = false
)
