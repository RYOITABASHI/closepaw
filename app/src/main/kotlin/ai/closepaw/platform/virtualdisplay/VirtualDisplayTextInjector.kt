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
import kotlinx.coroutines.NonCancellable
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
 * then try set-text on the focused editor before delivering keys or paste to the virtual display:
 *
 *  - printable ASCII → `input -d <vdDisplayId> text …` run as the shell user over the ADB shell,
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
        private val displayIdProvider: () -> Int,
        private val nodeActionPerformer: NodeActionPerformer,
        private val inputInjector: VirtualDisplayInputInjector,
        private val clipboardProvider: () -> ClipboardManager? = {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        },
        private val unattended: Boolean = false,
        private val isDispatchCancelled: (() -> Boolean)? = null,
        private val executeShellInput: suspend (Array<String>) -> Int,
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

        internal fun canUseClipboardPaste(unattended: Boolean, canRestore: Boolean): Boolean =
                !unattended || canRestore

        /**
         * The paste's own clip, provably: read back ([current] non-null), carrying this paste's
         * unique [label] AND exactly [text].
         */
        internal fun isPasteClip(current: ClipData?, label: String, text: String): Boolean {
            current ?: return false
            if (current.description?.label?.toString() != label) return false
            if (current.itemCount != 1) return false
            return current.getItemAt(0)?.text?.toString() == text
        }

        internal fun acceptFocusedSetText(outcome: Verdict): Boolean = when (outcome) {
            Verdict.VERIFIED, Verdict.VERIFIED_PARTIAL -> true
            Verdict.UNVERIFIED_CHANGED, Verdict.UNVERIFIED, Verdict.FAILED_UNCHANGED -> false
        }

        internal fun isStableSnapshot(first: Pair<String?, String>?, second: Pair<String?, String>?): Boolean =
                first != null && first == second

        /**
         * True when [text] can be delivered verbatim through `input text`: printable ASCII only.
         * `%` is excluded because `input text` interprets `%s` as a space; spaces themselves are
         * encoded as `%s` by [encodeForInputText].
         */
        fun isShellTypeable(text: String): Boolean =
                text.isNotEmpty() && text.all { it in ' '..'~' && it != '%' }

        /** Encode for a single `input text` argument (spaces → `%s`). Caller checked [isShellTypeable]. */
        fun encodeForInputText(text: String): String = text.replace(" ", "%s")

        /**
         * Shorter strings whose presence in the tree still indicates the payload arrived: the
         * first word (>= 3 chars) and the first 8 characters. Search boxes often echo only a
         * prefix or re-render into suggestions that start with the typed text.
         */
        fun verificationCandidates(text: String): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            val out = LinkedHashSet<String>()
            out.add(trimmed)
            trimmed.split(Regex("\\s+")).firstOrNull()?.takeIf { it.length >= 3 }?.let { out.add(it) }
            if (trimmed.length > 8) out.add(trimmed.take(8))
            return out.toList()
        }

        /**
         * @param payloadSeen full payload visible in the focused node or the VD tree (and was not
         *   visible before the injection)
         * @param prefixSeen a [verificationCandidates] prefix/word appeared that was not there before
         * @param treeChanged the VD window signature differs from before the injection
         * @param focusedUnchanged an accessibility-exposed focused field exists and its text is
         *   exactly what it was before the injection
         */
        fun verdict(
                payloadSeen: Boolean,
                prefixSeen: Boolean,
                treeChanged: Boolean,
                focusedUnchanged: Boolean,
        ): Verdict = when {
            payloadSeen -> Verdict.VERIFIED
            prefixSeen -> Verdict.VERIFIED_PARTIAL
            focusedUnchanged && !treeChanged -> Verdict.FAILED_UNCHANGED
            treeChanged -> Verdict.UNVERIFIED_CHANGED
            else -> Verdict.UNVERIFIED
        }
    }

    /** Outcome of post-injection verification, decided from observations only (unit-tested). */
    enum class Verdict { VERIFIED, VERIFIED_PARTIAL, UNVERIFIED_CHANGED, UNVERIFIED, FAILED_UNCHANGED }

    suspend fun inject(action: UIAction.InjectText): ActionResult {
        val displayId = displayIdProvider()
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) {
            // Safety invariant: key events and taps go to ClosePaw's own agent display only.
            return ActionResult.Failure("Virtual display not started (refusing to inject into display $displayId)")
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
                val tap = withContext(Dispatchers.IO) {
                    if (isDispatchCancelled?.invoke() == true) ActionResult.Cancelled("Cancelled before input dispatch")
                    else inputInjector.injectTap(fx, fy)
                }
                if (tap is ActionResult.Cancelled) return tap
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
        val candidates = verificationCandidates(action.text)

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
                delay(KEY_SETTLE_MS)
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

        // Observe from after clearing so a successful clear cannot verify failed delivery.
        val textBefore = nodeActionPerformer.readFocusedEditableText()
        val visibleBefore = candidates.filter { nodeActionPerformer.treeContainsText(it) }.toSet()
        val signatureBefore = nodeActionPerformer.treeSignature()

        suspend fun observe(): Pair<Verdict, NodeActionPerformer.FocusedTextState?> {
            val after = nodeActionPerformer.readFocusedEditableText()
            val focusedUnchanged = textBefore != null && after != null && after.text == textBefore.text
            val payloadInFocused = after != null && !focusedUnchanged && after.text.contains(action.text)
            val payloadInTree = payloadInFocused ||
                    (nodeActionPerformer.treeContainsText(action.text) && action.text !in visibleBefore)
            val prefixSeen = !payloadInTree && candidates.drop(1).any { c ->
                c !in visibleBefore && nodeActionPerformer.treeContainsText(c)
            }
            val treeChanged = nodeActionPerformer.treeSignature() != signatureBefore
            return verdict(payloadInTree, prefixSeen, treeChanged, focusedUnchanged) to after
        }

        // 3. Try clipboard-free accessibility delivery before shell text or paste.
        if (focused != null && textBefore != null) {
            if (nodeActionPerformer.performSetTextOnFocused(action.text, clear = false) is ActionResult.Success) {
                delay(KEY_SETTLE_MS)
                val (outcome, _) = observe()
                trail.add("text:a11y-focused-set:${outcome.name.lowercase()}")
                if (acceptFocusedSetText(outcome)) {
                    return ActionResult.Success("Typed on the VD [${trail.joinToString(",")}]")
                }
            } else {
                trail.add("text:a11y-focused-set:failed")
            }
        }

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

        // 4. Verify. Keys were delivered (exit 0 / paste ok); decide how sure we are from what the
        //    accessibility tree shows now. A retry costs the agent a whole turn, so only an
        //    exposed field that provably did not change is reported as a Failure.
        val (outcome, after) = observe()
        trail.add("verify:${outcome.name.lowercase()}")
        val detail = trail.joinToString(",")
        return when (outcome) {
            Verdict.VERIFIED ->
                    ActionResult.Success("Typed via keyboard injection (text seen on the VD) [$detail]")
            Verdict.VERIFIED_PARTIAL ->
                    ActionResult.Success(
                            "Typed via keyboard injection (prefix of the text seen on the VD) [$detail]"
                    )
            Verdict.UNVERIFIED_CHANGED ->
                    ActionResult.Success(
                            "Typed via keyboard injection; the VD content changed afterwards but the " +
                                    "text itself is not exposed to accessibility (unverified - check the " +
                                    "screenshot) [$detail]"
                    )
            Verdict.UNVERIFIED ->
                    ActionResult.Success(
                            "Keys delivered to the VD, but the field does not expose its text and " +
                                    "nothing observable changed (unverified - check the screenshot before " +
                                    "assuming it was typed) [$detail]"
                    )
            Verdict.FAILED_UNCHANGED ->
                    ActionResult.Failure(
                            "Keyboard injection did not change the focused field (still " +
                                    "'${after?.text}') [$detail]"
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
        // The unique label lets cleanup identify this paste's clip.
        val label = CLIP_LABEL + "-" + java.util.UUID.randomUUID().toString()
        var previous: ClipData? = null
        var wroteClip = false
        // The cleanup starts before the write, including cancellation as the write returns.
        try {
            val failure = withContext(Dispatchers.Main) {
                if (isDispatchCancelled?.invoke() == true) return@withContext "paste:cancelled"
                previous = readClipboard(clipboard)
                if (unattended) {
                    val first = snapshotValue(previous)
                    val second = snapshotValue(readClipboard(clipboard))
                    if (!canUseClipboardPaste(unattended, canRestore = isStableSnapshot(first, second))) {
                        return@withContext "paste:snapshot-unstable"
                    }
                }
                try {
                    val clip = ClipData.newPlainText(label, text)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        // Keeps the agent's text out of the system clipboard preview.
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    if (isDispatchCancelled?.invoke() == true) return@withContext "paste:cancelled"
                    clipboard.setPrimaryClip(clip)
                    wroteClip = true
                } catch (e: Exception) {
                    Log.w(TAG, "setPrimaryClip failed", e)
                    return@withContext "paste:clip-set-failed"
                }
                if (unattended && !isPasteClip(readClipboard(clipboard), label, text)) {
                    "paste:clipboard-ownership-unverified"
                } else {
                    null
                }
            }
            if (failure != null) {
                trail.add(failure)
                return false
            }
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
            // NonCancellable: a run stopped during the settle still gets this cleanup.
            if (wroteClip) {
                withContext(NonCancellable) {
                    delay(PASTE_SETTLE_MS)
                    withContext(Dispatchers.Main) {
                        try {
                            val current = readClipboard(clipboard)
                            val original = previous
                            if (isPasteClip(current, label, text)) {
                                if (original != null) {
                                    clipboard.setPrimaryClip(original)
                                } else if (android.os.Build.VERSION.SDK_INT >= 28) {
                                    clipboard.clearPrimaryClip()
                                }
                            } else if (unattended && current == null && original != null) {
                                // With ownership unknown, restoring the pre-paste snapshot is
                                // less harmful than leaving sensitive agent text behind. This
                                // last resort can overwrite a clip set by another app in the gap.
                                Log.w(TAG, "Clipboard unreadable during cleanup, attempting snapshot restore")
                                clipboard.setPrimaryClip(original)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "clipboard cleanup failed", e)
                        }
                    }
                }
            }
        }
    }

    private fun snapshotValue(clip: ClipData?): Pair<String?, String>? = try {
        if (clip?.itemCount != 1) null
        else clip.getItemAt(0)?.text?.toString()?.let { clip.description?.label?.toString() to it }
    } catch (_: Exception) {
        null
    }

    private fun readClipboard(clipboard: ClipboardManager): ClipData? = try {
        clipboard.primaryClip
    } catch (_: Exception) {
        null
    }

    /** Every shell command here is `input -d <vd> …`; refuse anything not bound to a VD. */
    private suspend fun shell(vararg command: String): Int {
        val displayArg = command.getOrNull(2)?.toIntOrNull()
        if (command.getOrNull(0) != "input" || command.getOrNull(1) != "-d" ||
                displayArg == null || displayArg <= Display.DEFAULT_DISPLAY) {
            Log.e(TAG, "Refusing shell input not targeted at a virtual display: ${command.take(3).joinToString(" ")}")
            return -1
        }
        // Quiet: the command carries the typed text, which must not reach logcat.
        return withContext(Dispatchers.IO) {
            if (isDispatchCancelled?.invoke() == true) -1
            else executeShellInput(arrayOf(*command))
        }
    }
}
