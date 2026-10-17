package ai.closepaw.platform.virtualdisplay

import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.SystemButtonType
import ai.closepaw.platform.UIAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Input injector for a virtual display via Shizuku.
 *
 * Handles tap, long-press, swipe, and system button injection. Encapsulates MotionEvent/KeyEvent
 * construction and display targeting.
 *
 * Long-press and swipe are cancellation-safe: if the coroutine is cancelled after DOWN is
 * delivered, a best-effort ACTION_CANCEL is sent to release the target UI from pressed/dragging
 * state.
 */
class VirtualDisplayInputInjector(
        private val shizuku: ShizukuClient,
        private val displayIdProvider: () -> Int
) {
    companion object {
        private const val TAG = "VirtualDisplayInputInjector"
    }

    fun injectTap(x: Int, y: Int): ActionResult = guarded { injectTapUnguarded(x, y) }

    private fun injectTapUnguarded(x: Int, y: Int): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        if (!supportsDisplayIdInjection()) {
            return shellTap(displayId, x, y)
        }

        val downTime = SystemClock.uptimeMillis()
        val tapDuration = ViewConfiguration.getTapTimeout().toLong()
        val down =
                motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        val up =
                motionEvent(
                        downTime,
                        downTime + tapDuration,
                        MotionEvent.ACTION_UP,
                        x.toFloat(),
                        y.toFloat()
                )

        val ok = shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)
        down.recycle()
        up.recycle()

        return if (ok) ActionResult.Success("Tap at ($x,$y)")
        else ActionResult.Failure("Tap inject failed at ($x,$y)")
    }

    suspend fun injectLongPress(x: Int, y: Int, durationMs: Long): ActionResult =
            guarded { injectLongPressUnguarded(x, y, durationMs) }

    private suspend fun injectLongPressUnguarded(x: Int, y: Int, durationMs: Long): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        if (!supportsDisplayIdInjection()) {
            return shellLongPress(displayId, x, y, durationMs)
        }

        val downTime = SystemClock.uptimeMillis()
        val down =
                motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())

        if (!shizuku.injectInputEvent(down)) {
            down.recycle()
            return ActionResult.Failure("Long press DOWN inject failed at ($x,$y)")
        }
        down.recycle()

        // DOWN delivered — ensure cleanup on cancellation or failure
        var upSent = false
        try {
            delay(durationMs)

            val up =
                    motionEvent(
                            downTime,
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            x.toFloat(),
                            y.toFloat()
                    )
            upSent = shizuku.injectInputEvent(up)
            up.recycle()

            return if (upSent) {
                ActionResult.Success("Long press at ($x,$y) for ${durationMs}ms")
            } else {
                ActionResult.Failure("Long press UP inject failed at ($x,$y)")
            }
        } finally {
            if (!upSent) {
                sendBestEffortCancel(downTime, x.toFloat(), y.toFloat())
            }
        }
    }

    suspend fun injectSwipe(action: UIAction.Swipe): ActionResult = guarded { injectSwipeUnguarded(action) }

    private suspend fun injectSwipeUnguarded(action: UIAction.Swipe): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        if (!supportsDisplayIdInjection()) {
            return shellSwipe(displayId, action)
        }

        val downTime = SystemClock.uptimeMillis()
        val steps = 20
        val stepMs = (action.durationMs / steps).coerceAtLeast(1)

        val down =
                motionEvent(
                        downTime,
                        downTime,
                        MotionEvent.ACTION_DOWN,
                        action.startX.toFloat(),
                        action.startY.toFloat()
                )
        if (!shizuku.injectInputEvent(down)) {
            down.recycle()
            return ActionResult.Failure("Swipe DOWN inject failed")
        }
        down.recycle()

        // DOWN delivered — ensure cleanup on cancellation or failure
        var completedCleanly = false
        var lastX = action.startX.toFloat()
        var lastY = action.startY.toFloat()
        try {
            for (i in 1..steps) {
                delay(stepMs)
                val t = i.toFloat() / steps
                lastX = action.startX + (action.endX - action.startX) * t
                lastY = action.startY + (action.endY - action.startY) * t
                val move =
                        motionEvent(
                                downTime,
                                SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_MOVE,
                                lastX,
                                lastY
                        )
                val moveOk = shizuku.injectInputEvent(move)
                move.recycle()
                if (!moveOk) {
                    return ActionResult.Failure("Swipe MOVE inject failed at step $i")
                }
            }

            val up =
                    motionEvent(
                            downTime,
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            action.endX.toFloat(),
                            action.endY.toFloat()
                    )
            completedCleanly = shizuku.injectInputEvent(up)
            up.recycle()

            return if (completedCleanly) {
                ActionResult.Success(
                        "Swipe (${action.startX},${action.startY}) → (${action.endX},${action.endY})"
                )
            } else {
                ActionResult.Failure("Swipe UP inject failed")
            }
        } finally {
            if (!completedCleanly) {
                sendBestEffortCancel(downTime, lastX, lastY)
            }
        }
    }

    fun injectSystemButton(button: SystemButtonType): ActionResult = guarded { injectSystemButtonUnguarded(button) }

    private fun injectSystemButtonUnguarded(button: SystemButtonType): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        val keyCode =
                when (button) {
                    SystemButtonType.BACK -> KeyEvent.KEYCODE_BACK
                    SystemButtonType.HOME -> KeyEvent.KEYCODE_HOME
                    SystemButtonType.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
                    SystemButtonType.ENTER -> KeyEvent.KEYCODE_ENTER
                }

        if (!supportsDisplayIdInjection()) {
            return shellKeyEvent(displayId, keyCode, button)
        }

        val now = SystemClock.uptimeMillis()
        val down = keyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode)
        val up = keyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode)

        val ok = shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)

        return if (ok) ActionResult.Success("System button: $button")
        else ActionResult.Failure("System button inject failed: $button")
    }

    /**
     * The only display this injector will ever target: ClosePaw's own virtual display. Null when
     * the VD is not running — and also when the provider would hand back the DEFAULT display,
     * which must never receive agent input (safety invariant, asserted on every injection).
     */
    private fun targetDisplayId(): Int? {
        val id = displayIdProvider()
        if (id == Display.INVALID_DISPLAY) return null
        if (id == Display.DEFAULT_DISPLAY) {
            Log.e(TAG, "Refusing to inject into the default display")
            return null
        }
        return id
    }

    private fun motionEvent(
            downTime: Long,
            eventTime: Long,
            action: Int,
            x: Float,
            y: Float
    ): MotionEvent {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        if (!setDisplayId(event, displayIdProvider())) {
            event.recycle()
            throw UntargetedInputEventException(action)
        }
        return event
    }

    /** Thrown when an event could not be bound to the VD; callers must not inject it. */
    class UntargetedInputEventException(action: Int) :
            IllegalStateException("InputEvent (action=$action) is not bound to the virtual display")

    /**
     * Inject one raw MotionEvent action to the virtual display.
     * Used by the Viewer to forward user touch input (down/move/up stream).
     */
    fun injectMotionAction(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
    ): Boolean {
        targetDisplayId() ?: return false
        if (action != MotionEvent.ACTION_DOWN &&
                action != MotionEvent.ACTION_MOVE &&
                action != MotionEvent.ACTION_UP &&
                action != MotionEvent.ACTION_CANCEL
        ) {
            return false
        }
        val event = motionEvent(downTime, eventTime, action, x, y)
        val ok = shizuku.injectInputEvent(event)
        event.recycle()
        return ok
    }

    /**
     * Whether setDisplayId reflection actually works (not just exists).
     * Verified once by round-tripping a displayId on a test MotionEvent.
     */
    private val displayIdInjectionVerified: Boolean by lazy {
        val method = setDisplayIdMethod ?: return@lazy false
        try {
            val test = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            method.invoke(test, 42)
            // Read back via reflection (getDisplayId is @hide on older APIs)
            val getId = android.view.InputEvent::class.java.getMethod("getDisplayId")
            val readBack = getId.invoke(test) as? Int
            test.recycle()
            val works = readBack == 42
            if (!works) {
                Log.w(TAG, "setDisplayId exists but has no effect (hidden API blocked), using shell fallback")
            } else {
                Log.i(TAG, "setDisplayId verified working")
            }
            works
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayId verification failed, using shell fallback", e)
            false
        }
    }

    fun supportsDisplayIdInjection(): Boolean = displayIdInjectionVerified

    /** Turns a failed display binding into a Failure instead of an untargeted injection. */
    private inline fun guarded(block: () -> ActionResult): ActionResult =
            try {
                block()
            } catch (e: UntargetedInputEventException) {
                Log.e(TAG, "Input event not bound to the virtual display; dropped", e)
                ActionResult.Failure(e.message ?: "Input event not bound to the virtual display")
            }

    // ---- Shell fallback helpers (used when setDisplayId reflection is unavailable) ----

    private fun shellTap(displayId: Int, x: Int, y: Int): ActionResult {
        val exitCode = shizuku.executeShellCommand(
                arrayOf("input", "-d", "$displayId", "tap", "$x", "$y")
        )
        return if (exitCode == 0) ActionResult.Success("Tap at ($x,$y) [shell]")
        else ActionResult.Failure("Shell tap failed at ($x,$y), exit=$exitCode")
    }

    private suspend fun shellLongPress(
            displayId: Int,
            x: Int,
            y: Int,
            durationMs: Long
    ): ActionResult {
        val exitCode = withContext(Dispatchers.IO) {
            shizuku.executeShellCommand(
                    arrayOf(
                            "input", "-d", "$displayId", "swipe",
                            "$x", "$y", "$x", "$y", "$durationMs"
                    )
            )
        }
        return if (exitCode == 0) {
            ActionResult.Success("Long press at ($x,$y) for ${durationMs}ms [shell]")
        } else {
            ActionResult.Failure("Shell long press failed at ($x,$y), exit=$exitCode")
        }
    }

    private suspend fun shellSwipe(displayId: Int, action: UIAction.Swipe): ActionResult {
        val exitCode = withContext(Dispatchers.IO) {
            shizuku.executeShellCommand(
                    arrayOf(
                            "input", "-d", "$displayId", "swipe",
                            "${action.startX}", "${action.startY}",
                            "${action.endX}", "${action.endY}",
                            "${action.durationMs}"
                    )
            )
        }
        return if (exitCode == 0) {
            ActionResult.Success(
                    "Swipe (${action.startX},${action.startY}) → (${action.endX},${action.endY}) [shell]"
            )
        } else {
            ActionResult.Failure("Shell swipe failed, exit=$exitCode")
        }
    }

    private fun shellKeyEvent(
            displayId: Int,
            keyCode: Int,
            button: SystemButtonType
    ): ActionResult {
        val exitCode = shizuku.executeShellCommand(
                arrayOf("input", "-d", "$displayId", "keyevent", "$keyCode")
        )
        return if (exitCode == 0) ActionResult.Success("System button: $button [shell]")
        else ActionResult.Failure("Shell keyevent failed: $button, exit=$exitCode")
    }

    private fun keyEvent(downTime: Long, eventTime: Long, action: Int, keyCode: Int): KeyEvent {
        val event = KeyEvent(downTime, eventTime, action, keyCode, 0)
        if (!setDisplayId(event, displayIdProvider())) throw UntargetedInputEventException(action)
        return event
    }

    /** Send a best-effort ACTION_CANCEL to release the target UI from pressed/dragging state. */
    private fun sendBestEffortCancel(downTime: Long, x: Float, y: Float) {
        try {
            val cancel =
                    motionEvent(
                            downTime,
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_CANCEL,
                            x,
                            y
                    )
            shizuku.injectInputEvent(cancel)
            cancel.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Best-effort ACTION_CANCEL failed", e)
        }
    }

    /**
     * Cached reflection handle for InputEvent.setDisplayId(int), which is @hide in AOSP.
     * HiddenApiBypass exemptions must be active (called in VirtualDisplayPlatform.start()).
     */
    private val setDisplayIdMethod: java.lang.reflect.Method? by lazy {
        runCatching {
                    android.view.InputEvent::class.java.getMethod(
                            "setDisplayId",
                            Int::class.javaPrimitiveType
                    )
                }
                .onFailure { Log.w(TAG, "InputEvent.setDisplayId not available", it) }
                .getOrNull()
    }

    private val getDisplayIdMethod: java.lang.reflect.Method? by lazy {
        runCatching { android.view.InputEvent::class.java.getMethod("getDisplayId") }.getOrNull()
    }

    /**
     * Bind [event] to display [id] and confirm it stuck. Returns false (never inject!) when the
     * id is not a virtual display or the hidden API did not take effect.
     */
    private fun setDisplayId(event: android.view.InputEvent, id: Int): Boolean {
        if (id == Display.INVALID_DISPLAY || id == Display.DEFAULT_DISPLAY) return false
        val method = setDisplayIdMethod ?: return false
        return try {
            method.invoke(event, id) // void method — null return is normal
            val readBack = getDisplayIdMethod?.invoke(event) as? Int
            if (readBack != null && readBack != id) {
                Log.e(TAG, "displayId mismatch after set: wanted=$id got=$readBack")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set displayId=$id on ${event.javaClass.simpleName}", e)
            false
        }
    }
}
