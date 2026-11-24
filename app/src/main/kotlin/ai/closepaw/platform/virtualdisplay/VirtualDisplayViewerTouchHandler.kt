package ai.closepaw.platform.virtualdisplay

import ai.closepaw.taskhelper.TaskHelperInput
import android.view.MotionEvent

/**
 * Handles touch forwarding from VirtualDisplayViewerActivity into the virtual display.
 *
 * Primary path uses raw MotionEvent injection with display targeting. Fallback path uses shell
 * input commands when hidden display-id injection is unavailable.
 */
internal class VirtualDisplayViewerTouchHandler(
        private val config: VirtualDisplayConfig,
        private val displayIdProvider: () -> Int,
        private val inputInjector: VirtualDisplayInputInjector,
        private val executeShellInput: suspend (Array<String>) -> Int,
        private val retireDisplay: (Int, InputOutcome) -> Unit,
) {
        companion object {
                private const val VIEWER_SWIPE_THRESHOLD_PX = 18f
        }

        private var viewerDownX = 0f
        private var viewerDownY = 0f
        private var viewerDownTime = 0L
        private var viewerMoved = false
        private var shellGesture = false
        private var rawDownDispatched = false
        private var gestureDisplayId = 0
        private var gestureDownTime = 0L

        suspend fun onViewerTouch(
                action: Int,
                x: Float,
                y: Float,
                downTime: Long,
                eventTime: Long,
                viewWidth: Int,
                viewHeight: Int
        ): Boolean {
                val displayId = displayIdProvider()
                val actionMasked = action and MotionEvent.ACTION_MASK
                val request = TaskHelperInputRequest(
                        TaskHelperInput.MOTION, displayId, x, y,
                        action = actionMasked, downTime = downTime, eventTime = eventTime
                )
                if (viewWidth <= 0 || viewHeight <= 0 || !request.valid) {
                        val activeDisplayId = gestureDisplayId
                        val hadRawDown = rawDownDispatched
                        resetGesture()
                        if (hadRawDown) {
                                retireDisplay(activeDisplayId, InputOutcome.INVALID_TARGET_OR_REQUEST)
                        }
                        return false
                }

                val targetX =
                        ((x / viewWidth) * config.width).coerceIn(0f, (config.width - 1).toFloat())
                val targetY =
                        ((y / viewHeight) * config.height)
                                .coerceIn(0f, (config.height - 1).toFloat())

                if (actionMasked == MotionEvent.ACTION_DOWN) {
                        gestureDisplayId = displayId
                        gestureDownTime = downTime
                        rawDownDispatched = false
                        shellGesture = !inputInjector.supportsRawViewerInput()
                } else if (gestureDisplayId != displayId || gestureDownTime != downTime) {
                        return false
                }
                val endsGesture = actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL

                if (!shellGesture) {
                        val result = inputInjector.injectMotionAction(actionMasked, targetX, targetY, downTime, eventTime)
                        val outcome = result.outcome
                        if (actionMasked == MotionEvent.ACTION_DOWN) {
                                rawDownDispatched = result.mayHaveDispatched
                        }
                        if (outcome == InputOutcome.CONFIRMED) {
                                if (endsGesture) resetGesture()
                                return true
                        }
                        if (actionMasked != MotionEvent.ACTION_DOWN || outcome != InputOutcome.UNAVAILABLE) {
                                val hadRawDown = rawDownDispatched
                                resetGesture()
                                if (outcome.blocksFallback()) {
                                        if (hadRawDown || outcome != InputOutcome.INVALID_TARGET_OR_REQUEST) {
                                                retireDisplay(displayId, outcome)
                                        }
                                } else if (hadRawDown &&
                                        !inputInjector.cancelViewerMotion(displayId, downTime, targetX, targetY)
                                ) {
                                        retireDisplay(displayId, outcome)
                                }
                                return false
                        }
                        shellGesture = true
                }

                if (!inputInjector.channelConfirmed()) {
                        resetGesture()
                        retireDisplay(displayId, InputOutcome.CHANNEL_UNAVAILABLE)
                        return false
                }
                val hadRawDown = rawDownDispatched
                if (endsGesture) resetGesture()
                val outcome = injectViaShell(
                        action = actionMasked,
                        x = targetX,
                        y = targetY,
                        eventTime = eventTime,
                        displayId = displayId
                )
                // A helper may have received DOWN before we switched to the buffered shell route.
                if (outcome == InputOutcome.CHANNEL_UNAVAILABLE) {
                        resetGesture()
                        retireDisplay(displayId, outcome)
                } else if (hadRawDown && (outcome != InputOutcome.CONFIRMED || actionMasked == MotionEvent.ACTION_CANCEL)) {
                        resetGesture()
                        retireDisplay(displayId, InputOutcome.UNCONFIRMED)
                }
                return outcome == InputOutcome.CONFIRMED
        }

        private fun resetGesture() {
                gestureDisplayId = 0
                rawDownDispatched = false
        }

        private suspend fun injectViaShell(
                action: Int,
                x: Float,
                y: Float,
                eventTime: Long,
                displayId: Int
        ): InputOutcome {
                when (action) {
                        MotionEvent.ACTION_DOWN -> {
                                viewerDownX = x
                                viewerDownY = y
                                viewerDownTime = eventTime
                                viewerMoved = false
                                return InputOutcome.CONFIRMED
                        }
                        MotionEvent.ACTION_MOVE -> {
                                if (!viewerMoved) {
                                        val dx = x - viewerDownX
                                        val dy = y - viewerDownY
                                        if (dx * dx + dy * dy >=
                                                        VIEWER_SWIPE_THRESHOLD_PX *
                                                                VIEWER_SWIPE_THRESHOLD_PX
                                        ) {
                                                viewerMoved = true
                                        }
                                }
                                return InputOutcome.CONFIRMED
                        }
                        MotionEvent.ACTION_CANCEL -> {
                                viewerMoved = false
                                viewerDownTime = 0L
                                return InputOutcome.CONFIRMED
                        }
                        MotionEvent.ACTION_UP -> {
                                val durationMs = (eventTime - viewerDownTime).coerceIn(16L, 1500L)
                                val command =
                                        if (viewerMoved) {
                                                arrayOf(
                                                        "input",
                                                        "-d",
                                                        "$displayId",
                                                        "swipe",
                                                        "${viewerDownX.toInt()}",
                                                        "${viewerDownY.toInt()}",
                                                        "${x.toInt()}",
                                                        "${y.toInt()}",
                                                        "$durationMs"
                                                )
                                        } else {
                                                arrayOf(
                                                        "input",
                                                        "-d",
                                                        "$displayId",
                                                        "tap",
                                                        "${x.toInt()}",
                                                        "${y.toInt()}"
                                                )
                                        }
                                viewerMoved = false
                                viewerDownTime = 0L
                                val exitCode = executeShellInput(command)
                                return when {
                                        !inputInjector.channelConfirmed(reconnect = false) -> InputOutcome.CHANNEL_UNAVAILABLE
                                        exitCode == 0 -> InputOutcome.CONFIRMED
                                        else -> InputOutcome.UNCONFIRMED
                                }
                        }
                        else -> return InputOutcome.INVALID_TARGET_OR_REQUEST
                }
        }
}
