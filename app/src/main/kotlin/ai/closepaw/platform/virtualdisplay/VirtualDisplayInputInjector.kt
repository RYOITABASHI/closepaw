package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperInput
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Input injector for the session virtual display.
 *
 * Handles tap, long-press, swipe, and system button injection. Encapsulates MotionEvent/KeyEvent
 * construction and display targeting.
 *
 * Long-press and swipe are cancellation-safe: if the coroutine is cancelled after DOWN is
 * delivered, a best-effort ACTION_CANCEL is sent to release the target UI from pressed/dragging
 * state.
 */
class VirtualDisplayInputInjector internal constructor(
        private val shizuku: ShizukuClient,
        private val displayIdProvider: () -> Int,
        private val shell: suspend (Array<String>) -> Int,
        private val confirmChannel: suspend (Boolean) -> Boolean,
        // Before `helper` so existing trailing-lambda call sites keep binding the helper.
        private val secondChannel: SecondChannelProbe? = null,
        private val helper: (suspend (TaskHelperInputRequest) -> InputOutcome)? = null,
) {
    /** The channel used by the last agent input ("adb" or "shizuku"); for audits and status. */
    @Volatile internal var lastInputChannel: String = "adb"
        private set

    companion object {
        private const val TAG = "VirtualDisplayInputInjector"
    }

    suspend fun injectTap(x: Int, y: Int): ActionResult = guarded { injectTapUnguarded(x, y) }

    private suspend fun injectTapUnguarded(x: Int, y: Int): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        return route(
                TaskHelperInputRequest(TaskHelperInput.TAP, displayId, x.toFloat(), y.toFloat()),
                "Tap at ($x,$y)",
        ) {
            if (!supportsDisplayIdInjection()) {
                return@route shellTap(displayId, x, y)
            }

            val downTime = SystemClock.uptimeMillis()
            val tapDuration = ViewConfiguration.getTapTimeout().toLong()
            val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN,
                x.toFloat(), y.toFloat(), displayId)
            val ok = try {
                val up = motionEvent(downTime, downTime + tapDuration, MotionEvent.ACTION_UP,
                    x.toFloat(), y.toFloat(), displayId)
                try {
                    shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)
                } finally {
                    up.recycle()
                }
            } finally {
                down.recycle()
            }
            if (!ok) sendBestEffortCancel(downTime, x.toFloat(), y.toFloat(), displayId)

            return@route if (ok) ActionResult.Success("Tap at ($x,$y)")
            else ActionResult.Failure("Tap inject failed at ($x,$y)")
        }
    }

    suspend fun injectLongPress(x: Int, y: Int, durationMs: Long): ActionResult =
            guarded { injectLongPressUnguarded(x, y, durationMs) }

    private suspend fun injectLongPressUnguarded(x: Int, y: Int, durationMs: Long): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        return route(
                TaskHelperInputRequest(TaskHelperInput.LONG_PRESS, displayId, x.toFloat(), y.toFloat(),
                    durationMs = durationMs),
                "Long press at ($x,$y) for ${durationMs}ms",
        ) {
            if (!supportsDisplayIdInjection()) {
                return@route shellLongPress(displayId, x, y, durationMs)
            }

            val downTime = SystemClock.uptimeMillis()
            val down =
                    motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), displayId)

            if (!shizuku.injectInputEvent(down)) {
                down.recycle()
                return@route ActionResult.Failure("Long press DOWN inject failed at ($x,$y)")
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
                                y.toFloat(), displayId
                        )
                upSent = shizuku.injectInputEvent(up)
                up.recycle()

                return@route if (upSent) {
                    ActionResult.Success("Long press at ($x,$y) for ${durationMs}ms")
                } else {
                    ActionResult.Failure("Long press UP inject failed at ($x,$y)")
                }
            } finally {
                if (!upSent) {
                    sendBestEffortCancel(downTime, x.toFloat(), y.toFloat(), displayId)
                }
            }
        }
    }

    suspend fun injectSwipe(action: UIAction.Swipe): ActionResult = guarded { injectSwipeUnguarded(action) }

    private suspend fun injectSwipeUnguarded(action: UIAction.Swipe): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        return route(
                TaskHelperInputRequest(TaskHelperInput.SWIPE, displayId, action.startX.toFloat(),
                    action.startY.toFloat(), action.endX.toFloat(), action.endY.toFloat(), action.durationMs),
                "Swipe (${action.startX},${action.startY}) → (${action.endX},${action.endY})",
        ) {
            if (!supportsDisplayIdInjection()) {
                return@route shellSwipe(displayId, action)
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
                            action.startY.toFloat(), displayId
                    )
            if (!shizuku.injectInputEvent(down)) {
                down.recycle()
                return@route ActionResult.Failure("Swipe DOWN inject failed")
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
                                    lastY, displayId
                            )
                    val moveOk = shizuku.injectInputEvent(move)
                    move.recycle()
                    if (!moveOk) {
                        return@route ActionResult.Failure("Swipe MOVE inject failed at step $i")
                    }
                }

                val up =
                        motionEvent(
                                downTime,
                                SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_UP,
                                action.endX.toFloat(),
                                action.endY.toFloat(), displayId
                        )
                completedCleanly = shizuku.injectInputEvent(up)
                up.recycle()

                return@route if (completedCleanly) {
                    ActionResult.Success(
                            "Swipe (${action.startX},${action.startY}) → (${action.endX},${action.endY})"
                    )
                } else {
                    ActionResult.Failure("Swipe UP inject failed")
                }
            } finally {
                if (!completedCleanly) {
                    sendBestEffortCancel(downTime, lastX, lastY, displayId)
                }
            }
        }
    }

    suspend fun injectSystemButton(button: SystemButtonType): ActionResult = guarded { injectSystemButtonUnguarded(button) }

    private suspend fun injectSystemButtonUnguarded(button: SystemButtonType): ActionResult {
        val displayId = targetDisplayId()
                ?: return ActionResult.Failure("Virtual display not started (refusing to inject into display ${displayIdProvider()})")

        val keyCode =
                when (button) {
                    SystemButtonType.BACK -> KeyEvent.KEYCODE_BACK
                    SystemButtonType.HOME -> KeyEvent.KEYCODE_HOME
                    SystemButtonType.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
                    SystemButtonType.ENTER -> KeyEvent.KEYCODE_ENTER
                }

        return route(
                TaskHelperInputRequest(TaskHelperInput.KEY, displayId, action = keyCode),
                "System button: $button",
        ) {
            if (!supportsDisplayIdInjection()) {
                return@route shellKeyEvent(displayId, keyCode, button)
            }

            val now = SystemClock.uptimeMillis()
            val down = keyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, displayId)
            val up = keyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, displayId)

            val ok = shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)
            if (!ok && targetDisplayId() == displayId) {
                val cancelledUp = KeyEvent.changeFlags(up, KeyEvent.FLAG_CANCELED)
                if (setDisplayId(cancelledUp, displayId)) shizuku.injectInputEvent(cancelledUp)
            }

            return@route if (ok) ActionResult.Success("System button: $button")
            else ActionResult.Failure("System button inject failed: $button")
        }
    }

    /**
     * The only display this injector will ever target: ClosePaw's own virtual display. Null when
     * the VD is not running — and also when the provider would hand back the DEFAULT display,
     * which must never receive agent input (safety invariant, asserted on every injection).
     */
    private fun targetDisplayId(): Int? {
        val id = displayIdProvider()
        if (id <= Display.DEFAULT_DISPLAY) {
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
            y: Float,
            displayId: Int,
    ): MotionEvent {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        if (!setDisplayId(event, displayId)) {
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
    internal suspend fun injectMotionAction(
            action: Int,
            x: Float,
            y: Float,
            downTime: Long,
            eventTime: Long,
    ): ViewerMotionResult {
        val invalid = InputOutcome.INVALID_TARGET_OR_REQUEST
        val unavailable = InputOutcome.CHANNEL_UNAVAILABLE
        val displayId = targetDisplayId() ?: return ViewerMotionResult(invalid)
        val request = TaskHelperInputRequest(TaskHelperInput.MOTION, displayId, x, y,
            action = action, downTime = downTime, eventTime = eventTime)
        if (!request.valid) return ViewerMotionResult(invalid)
        if (!channelConfirmed()) return ViewerMotionResult(unavailable)
        if (targetDisplayId() != displayId) return ViewerMotionResult(invalid)

        val outcome = helper?.invoke(request)
        logHelper(request, outcome)
        val helperMayHaveDispatched = outcome?.mayHaveDispatchedMotion() == true
        if (outcome.blocksFallback()) {
            // Target invalidation can also be reported after the helper has dispatched.
            return ViewerMotionResult(checkNotNull(outcome), mayHaveDispatched = outcome == invalid)
        }
        if (outcome != null && !channelConfirmed(reconnect = false)) {
            return ViewerMotionResult(unavailable, helperMayHaveDispatched)
        }
        if (targetDisplayId() != displayId) return ViewerMotionResult(invalid, helperMayHaveDispatched)
        if (outcome == InputOutcome.CONFIRMED) return ViewerMotionResult(outcome)
        if (!supportsDisplayIdInjection()) {
            return ViewerMotionResult(InputOutcome.UNAVAILABLE, helperMayHaveDispatched)
        }
        currentCoroutineContext().ensureActive()
        return try {
            val event = motionEvent(downTime, eventTime, action, x, y, displayId)
            val accepted = try { shizuku.injectInputEvent(event) } finally { event.recycle() }
            Log.i(TAG, "input kind=viewer route=shizuku accepted=$accepted")
            val result = when {
                !channelConfirmed(reconnect = false) -> unavailable
                targetDisplayId() != displayId -> invalid
                accepted -> InputOutcome.CONFIRMED
                else -> InputOutcome.REJECTED
            }
            // The transport also returns false after an exception during dispatch.
            ViewerMotionResult(result, mayHaveDispatched = true)
        } catch (_: UntargetedInputEventException) {
            ViewerMotionResult(invalid, helperMayHaveDispatched)
        }
    }

    internal suspend fun channelConfirmed(reconnect: Boolean = true): Boolean {
        val confirmed = confirmChannel(reconnect)
        currentCoroutineContext().ensureActive()
        return confirmed
    }

    internal suspend fun cancelViewerMotion(displayId: Int, downTime: Long, x: Float, y: Float): Boolean {
        if (targetDisplayId() != displayId || !channelConfirmed(reconnect = false) || !supportsDisplayIdInjection()) {
            return false
        }
        return sendBestEffortCancel(downTime, x, y, displayId)
    }

    internal fun supportsRawViewerInput(): Boolean = helper != null || supportsDisplayIdInjection()

    private suspend inline fun route(
        request: TaskHelperInputRequest,
        success: String,
        fallback: () -> ActionResult,
    ): ActionResult {
        // channelConfirmed() reconnects once (the short self-repair) before giving up.
        if (!channelConfirmed()) {
            // Nothing has been sent on any route yet, so a second channel cannot duplicate input.
            val decision = secondChannelDecision(request.displayId)
            if (decision !is SecondChannelPolicy.Decision.Allow) {
                Log.i(TAG, "input kind=${request.kind} route=none adb=unavailable second_channel=refused(" +
                    "${(decision as SecondChannelPolicy.Decision.Refuse).reason})")
                return channelUnavailable()
            }
            currentCoroutineContext().ensureActive()
            // Re-verify the target immediately before injecting (guard 3): same VD id, still present.
            if (targetDisplayId() != request.displayId ||
                secondChannel?.displayExists(request.displayId) != true) {
                return ActionResult.Failure("Input dispatch failed [${InputOutcome.INVALID_TARGET_OR_REQUEST}]")
            }
            // Recorded before dispatch so a failed or partial Shizuku operation is never logged as adb.
            recordChannel("shizuku")
            val result = fallback()
            Log.i(TAG, "input kind=${request.kind} route=shizuku_second_channel adb=unavailable " +
                "accepted=${result is ActionResult.Success}")
            if (targetDisplayId() != request.displayId) {
                return ActionResult.Failure("Input dispatch failed [${InputOutcome.INVALID_TARGET_OR_REQUEST}]")
            }
            return if (result is ActionResult.Success) ActionResult.Success("${result.message} [second-channel:shizuku]")
            else result
        }
        recordChannel("adb")
        if (targetDisplayId() != request.displayId) {
            return ActionResult.Failure("Input dispatch failed [${InputOutcome.INVALID_TARGET_OR_REQUEST}]")
        }
        // Oversized legacy durations continue through the incumbent path.
        val outcome = if (request.valid) helper?.invoke(request) else null
        logHelper(request, outcome)
        if (outcome.blocksFallback()) return ActionResult.Failure("Input dispatch failed [$outcome]")
        if (outcome != null && !channelConfirmed(reconnect = false)) return channelUnavailable()
        if (targetDisplayId() != request.displayId) {
            return ActionResult.Failure("Input dispatch failed [${InputOutcome.INVALID_TARGET_OR_REQUEST}]")
        }
        if (outcome == InputOutcome.CONFIRMED) return ActionResult.Success(success)
        currentCoroutineContext().ensureActive()
        val result = fallback()
        if (!channelConfirmed(reconnect = false)) return channelUnavailable()
        if (targetDisplayId() != request.displayId) {
            return ActionResult.Failure("Input dispatch failed [${InputOutcome.INVALID_TARGET_OR_REQUEST}]")
        }
        Log.i(TAG, "input kind=${request.kind} route=incumbent accepted=${result is ActionResult.Success}")
        return if (outcome != null && result is ActionResult.Failure) {
            ActionResult.Failure("Helper input $outcome; ${result.reason}")
        } else result
    }

    private fun recordChannel(channel: String) {
        lastInputChannel = channel
        VdInputChannelState.record(channel, System.currentTimeMillis())
    }

    /** Evaluated immediately before a second-channel injection (guards 1, 3, 4, 5). */
    private fun secondChannelDecision(displayId: Int): SecondChannelPolicy.Decision {
        val probe = secondChannel ?: return SecondChannelPolicy.Decision.Refuse("no_probe")
        val target = targetDisplayId()?.takeIf { it == displayId }
        return SecondChannelPolicy.decide(
            enabled = probe.enabled(),
            binderAlive = probe.binderAlive(),
            serverUid = probe.serverUid(),
            targetDisplayId = target,
            displayExists = target != null && probe.displayExists(target),
            displayIdInjection = supportsDisplayIdInjection(),
            canObserve = probe.canObserve(),
        )
    }

    private fun channelUnavailable() =
        ActionResult.Failure("Input dispatch failed [${InputOutcome.CHANNEL_UNAVAILABLE}]")

    private fun logHelper(request: TaskHelperInputRequest, outcome: InputOutcome?) {
        if (outcome != null) Log.i(TAG, "input kind=${request.kind} route=helper outcome=$outcome")
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

    private suspend fun shellTap(displayId: Int, x: Int, y: Int): ActionResult {
        val exitCode = shell(
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
        val exitCode = shell(
                    arrayOf(
                            "input", "-d", "$displayId", "swipe",
                            "$x", "$y", "$x", "$y", "$durationMs"
                    )
            )
        return if (exitCode == 0) {
            ActionResult.Success("Long press at ($x,$y) for ${durationMs}ms [shell]")
        } else {
            ActionResult.Failure("Shell long press failed at ($x,$y), exit=$exitCode")
        }
    }

    private suspend fun shellSwipe(displayId: Int, action: UIAction.Swipe): ActionResult {
        val exitCode = shell(
                    arrayOf(
                            "input", "-d", "$displayId", "swipe",
                            "${action.startX}", "${action.startY}",
                            "${action.endX}", "${action.endY}",
                            "${action.durationMs}"
                    )
            )
        return if (exitCode == 0) {
            ActionResult.Success(
                    "Swipe (${action.startX},${action.startY}) → (${action.endX},${action.endY}) [shell]"
            )
        } else {
            ActionResult.Failure("Shell swipe failed, exit=$exitCode")
        }
    }

    private suspend fun shellKeyEvent(
            displayId: Int,
            keyCode: Int,
            button: SystemButtonType
    ): ActionResult {
        val exitCode = shell(
                arrayOf("input", "-d", "$displayId", "keyevent", "$keyCode")
        )
        return if (exitCode == 0) ActionResult.Success("System button: $button [shell]")
        else ActionResult.Failure("Shell keyevent failed: $button, exit=$exitCode")
    }

    private fun keyEvent(downTime: Long, eventTime: Long, action: Int, keyCode: Int, displayId: Int): KeyEvent {
        val event = KeyEvent(downTime, eventTime, action, keyCode, 0)
        if (!setDisplayId(event, displayId)) throw UntargetedInputEventException(action)
        return event
    }

    /** Send a best-effort ACTION_CANCEL to release the target UI from pressed/dragging state. */
    private fun sendBestEffortCancel(downTime: Long, x: Float, y: Float, displayId: Int): Boolean {
        if (targetDisplayId() != displayId) return false
        return try {
            val cancel =
                    motionEvent(
                            downTime,
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_CANCEL,
                            x,
                            y, displayId
                    )
            try { shizuku.injectInputEvent(cancel) } finally { cancel.recycle() }
        } catch (e: Exception) {
            Log.w(TAG, "Best-effort ACTION_CANCEL failed", e)
            false
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
        if (id <= Display.DEFAULT_DISPLAY) return false
        val method = setDisplayIdMethod ?: return false
        return try {
            method.invoke(event, id) // void method — null return is normal
            val readBack = getDisplayIdMethod?.invoke(event) as? Int
            if (readBack != id) {
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
