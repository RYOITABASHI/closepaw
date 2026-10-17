package ai.closepaw.platform.virtualdisplay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.NodeActionPerformer
import ai.closepaw.platform.UIAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Keyboard-style text entry on the virtual display.
 *
 * `ACTION_SET_TEXT` is the first choice for every platform, but some apps (Spotify's Compose
 * text fields, WebViews, custom editors) reject it or silently ignore it. On the main display
 * ClosePaw then taps the field and types through the IME; on the virtual display that fallback
 * is disabled because the IME would pop up on the user's real screen. This class is the
 * VD-only replacement: focus the field through accessibility (or a Shizuku tap on the VD),
 * then deliver the text as key events to the virtual display:
 *
 *  - printable ASCII → `input -d <vdDisplayId> text …` run as the shell user through Shizuku,
 *    which maps every character through the virtual keyboard's KeyCharacterMap;
 *  - anything else (CJK, emoji, `%` which `input text` treats as an escape) → clipboard +
 *    `ACTION_PASTE` on the focused node, falling back to `KEYCODE_PASTE` on the VD.
 *
 * Security posture: every injected event is addressed to ClosePaw's own agent display
 * ([displayIdProvider]); the default display is never a target, and the injector is only
 * reachable while the VD platform holds a running lease (an active session).
 */
class VirtualDisplayTextInjector(
        private val context: Context,
        private val shizuku: ShizukuClient,
        private val displayIdProvider: () -> Int,
        private val nodeActionPerformer: NodeActionPerformer,
        private val inputInjector: VirtualDisplayInputInjector,
        private val clipboardProvider: () -> ClipboardManager? = {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        },
) {
    companion object {
        private const val TAG = "VDTextInjector"
        private const val FOCUS_SETTLE_MS = 150L
        private const val TAP_SETTLE_MS = 450L
        private const val KEY_SETTLE_MS = 120L
        private const val PASTE_SETTLE_MS = 250L
        private const val CLIP_LABEL = "closepaw-agent-text"
        private const val KEYCODE_CTRL_LEFT = KeyEvent.KEYCODE_CTRL_LEFT
        private const val KEYCODE_A = KeyEvent.KEYCODE_A
        private const val KEYCODE_DEL = KeyEvent.KEYCODE_DEL
        private const val KEYCODE_PASTE = 279 // KeyEvent.KEYCODE_PASTE (API 24+)

        /**
         * True when [text] can be delivered verbatim through `input text`: printable ASCII only.
         * `%` is excluded because `input text` interprets `%s` as a space; spaces themselves are
         * encoded as `%s` by [encodeForInputText].
         */
        fun isShellTypeable(text: String): Boolean =
                text.isNotEmpty() && text.all { it in ' '..'~' && it != '%' }

        /** Encode for a single `input text` argument (spaces → `%s`). Caller checked [isShellTypeable]. */
        fun encodeForInputText(text: String): String = text.replace(" ", "%s")
    }

    suspend fun inject(action: UIAction.InjectText): ActionResult {
        val displayId = displayIdProvider()
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }
        if (action.text.isEmpty() && !action.clear) {
            return ActionResult.Success("Nothing to type")
        }
        val trail = mutableListOf<String>()

        // 1. Focus the target field. Accessibility first (no IME round-trip); when the field is
        //    not exposed as a text node (Spotify's Compose name field) or nothing editable reports
        //    focus afterwards, tap it on the virtual display through Shizuku. The tap is addressed
        //    to the VD only; the IME is kept hidden by VirtualDisplayPlatform while this runs.
        val fx = action.focusX
        val fy = action.focusY
        var focused: NodeActionPerformer.FocusedTextState? = null
        if (fx != null && fy != null) {
            val focusResult = nodeActionPerformer.performFocusNodeAt(fx, fy)
            if (focusResult is ActionResult.Success) {
                trail.add("focus:a11y")
                delay(FOCUS_SETTLE_MS)
                focused = nodeActionPerformer.readFocusedEditableText()
            } else {
                trail.add("focus:a11y-failed(${(focusResult as? ActionResult.Failure)?.reason})")
            }
            if (focused == null) {
                val tap = withContext(Dispatchers.IO) { inputInjector.injectTap(fx, fy) }
                if (tap is ActionResult.Failure) {
                    return ActionResult.Failure(
                            "Could not tap field at ($fx,$fy) on the virtual display: ${tap.reason}"
                    )
                }
                trail.add("focus:vd-tap")
                // Compose fields need a moment after the touch before they take key events.
                delay(TAP_SETTLE_MS)
                focused = nodeActionPerformer.readFocusedEditableText()
            }
        } else {
            focused = nodeActionPerformer.readFocusedEditableText()
        }
        // No focused editable node is NOT a reason to stop: fields that hide from accessibility
        // still receive key events once tapped. Inject blind (target is still only our VD) and
        // verify afterwards through the tree.
        trail.add(if (focused != null) "focused:a11y" else "focused:none(blind)")
        val textVisibleBefore = nodeActionPerformer.treeContainsText(action.text)

        // 2. Clear existing content when asked.
        if (action.clear) {
            val cleared =
                    if (focused != null && focused.text.isNotEmpty()) {
                        nodeActionPerformer.performSetTextOnFocused("", clear = true)
                    } else {
                        ActionResult.Failure("no a11y text to clear")
                    }
            if (cleared is ActionResult.Success) {
                trail.add("clear:a11y")
            } else if (focused == null || focused.text.isNotEmpty()) {
                // Select all + delete as key events on the VD (blind fields included).
                val selectAll = shell("input", "-d", "$displayId", "keycombination",
                        "$KEYCODE_CTRL_LEFT", "$KEYCODE_A")
                val del = if (selectAll == 0) shell("input", "-d", "$displayId", "keyevent", "$KEYCODE_DEL") else -1
                trail.add(if (del == 0) "clear:keys" else "clear:failed")
                delay(KEY_SETTLE_MS)
            }
        }

        if (action.text.isEmpty()) {
            return ActionResult.Success("Cleared focused field [${trail.joinToString(",")}]")
        }

        // 3. Deliver the text to the virtual display.
        val delivered: Boolean =
                if (isShellTypeable(action.text)) {
                    val exit = shell("input", "-d", "$displayId", "text", encodeForInputText(action.text))
                    trail.add(if (exit == 0) "text:shell" else "text:shell-exit=$exit")
                    if (exit == 0) {
                        true
                    } else {
                        pasteViaClipboard(displayId, action.text, trail, focused != null)
                    }
                } else {
                    pasteViaClipboard(displayId, action.text, trail, focused != null)
                }
        if (!delivered) {
            return ActionResult.Failure(
                    "Keyboard injection on the virtual display failed [${trail.joinToString(",")}]"
            )
        }
        delay(if (trail.last().startsWith("text:shell")) KEY_SETTLE_MS else PASTE_SETTLE_MS)

        // 4. Verify: focused node text first, then anywhere in the VD window's tree.
        val detail = trail.joinToString(",")
        val after = nodeActionPerformer.readFocusedEditableText()
        if (after != null && after.text.contains(action.text)) {
            return ActionResult.Success("Typed via keyboard injection [$detail]")
        }
        val visibleNow = nodeActionPerformer.treeContainsText(action.text)
        return when {
            visibleNow && !textVisibleBefore ->
                    ActionResult.Success("Typed via keyboard injection (seen in the VD tree) [$detail]")
            visibleNow ->
                    ActionResult.Success(
                            "Typed via keyboard injection; text is in the VD tree (was already present " +
                                    "before, so this is unverified) [$detail]"
                    )
            after != null && focused != null && after.text == focused.text ->
                    ActionResult.Failure(
                            "Keyboard injection did not change the focused field (still " +
                                    "'${after.text}') [$detail]"
                    )
            after != null ->
                    ActionResult.Success(
                            "Typed via keyboard injection; focused field now '${after.text}' [$detail]"
                    )
            else ->
                    ActionResult.Failure(
                            "Keys were delivered to the virtual display but '${action.text}' is not " +
                                    "visible in its accessibility tree afterwards; the field may not have " +
                                    "accepted focus. Take a screenshot to check before retrying [$detail]"
                    )
        }
    }

    private suspend fun pasteViaClipboard(
            displayId: Int,
            text: String,
            trail: MutableList<String>,
            hasFocusedNode: Boolean
    ): Boolean {
        val clipboard = withContext(Dispatchers.Main) { clipboardProvider() }
        if (clipboard == null) {
            trail.add("paste:no-clipboard")
            return false
        }
        val set = withContext(Dispatchers.Main) {
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
                true
            } catch (e: Exception) {
                Log.w(TAG, "setPrimaryClip failed", e)
                false
            }
        }
        if (!set) {
            trail.add("paste:clip-set-failed")
            return false
        }
        try {
            if (hasFocusedNode) {
                val a11y = nodeActionPerformer.performPasteOnFocused()
                if (a11y is ActionResult.Success) {
                    trail.add("paste:a11y")
                    return true
                }
            }
            val exit = shell("input", "-d", "$displayId", "keyevent", "$KEYCODE_PASTE")
            trail.add(if (exit == 0) "paste:keyevent" else "paste:failed")
            return exit == 0
        } finally {
            // Do not leave agent text in the user's clipboard. The paste target already
            // consumed it; a tiny delay lets the paste actually read the clip first.
            delay(PASTE_SETTLE_MS)
            withContext(Dispatchers.Main) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        val current = clipboard.primaryClipDescription
                        if (current?.label == CLIP_LABEL) clipboard.clearPrimaryClip()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "clearPrimaryClip failed", e)
                }
            }
        }
    }

    private suspend fun shell(vararg command: String): Int =
            withContext(Dispatchers.IO) { shizuku.executeShellCommand(arrayOf(*command)) }
}
