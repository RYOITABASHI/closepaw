package ai.closepaw.platform.virtualdisplay

import android.content.ClipData
import android.content.ClipDescription
import ai.closepaw.platform.ActionResult
import ai.closepaw.platform.NodeActionPerformer
import ai.closepaw.platform.UIAction
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VirtualDisplayTextInjectorTest {

    @Test
    fun `printable ascii goes through input text with spaces encoded`() {
        assertThat(VirtualDisplayTextInjector.isShellTypeable("Smells Like 1991")).isTrue()
        assertThat(VirtualDisplayTextInjector.encodeForInputText("Smells Like 1991")).isEqualTo("Smells%sLike%s1991")
        assertThat(VirtualDisplayTextInjector.isShellTypeable("it\'s \"quoted\" & <ok>!")).isTrue()
    }

    @Test
    fun `non ascii, percent and control characters take the clipboard path`() {
        assertThat(VirtualDisplayTextInjector.isShellTypeable("1991\u5e74\u306e\u9999\u308a")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("100% done")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("tab\there")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("line\nbreak")).isFalse()
        assertThat(VirtualDisplayTextInjector.isShellTypeable("")).isFalse()
    }

    @Test
    fun `verification candidates include the first word and an 8 char prefix`() {
        assertThat(VirtualDisplayTextInjector.verificationCandidates("Nirvana Come As You Are"))
            .containsExactly("Nirvana Come As You Are", "Nirvana", "Nirvana ").inOrder()
        assertThat(VirtualDisplayTextInjector.verificationCandidates("ok")).containsExactly("ok")
        assertThat(VirtualDisplayTextInjector.verificationCandidates("  ")).isEmpty()
    }

    @Test
    fun `verdict only fails when an exposed field provably did not change`() {
        assertThat(VirtualDisplayTextInjector.verdict(payloadSeen = true, prefixSeen = false, treeChanged = false, focusedUnchanged = false)).isEqualTo(VirtualDisplayTextInjector.Verdict.VERIFIED)
        assertThat(VirtualDisplayTextInjector.verdict(false, true, false, false)).isEqualTo(VirtualDisplayTextInjector.Verdict.VERIFIED_PARTIAL)
        // Spotify search: text not exposed, results re-rendered -> continue, flagged unverified.
        assertThat(VirtualDisplayTextInjector.verdict(false, false, true, false)).isEqualTo(VirtualDisplayTextInjector.Verdict.UNVERIFIED_CHANGED)
        // Blind field, nothing observable at all -> still not a retry-worthy failure.
        assertThat(VirtualDisplayTextInjector.verdict(false, false, false, false)).isEqualTo(VirtualDisplayTextInjector.Verdict.UNVERIFIED)
        // Exposed focused field with identical text and a frozen tree -> honest failure.
        assertThat(VirtualDisplayTextInjector.verdict(false, false, false, true)).isEqualTo(VirtualDisplayTextInjector.Verdict.FAILED_UNCHANGED)
        // Exposed field unchanged but the window moved (results list) -> not a failure.
        assertThat(VirtualDisplayTextInjector.verdict(false, false, true, true)).isEqualTo(VirtualDisplayTextInjector.Verdict.UNVERIFIED_CHANGED)
    }

    @Test
    fun `unattended paste requires clipboard restoration capability`() {
        assertThat(VirtualDisplayTextInjector.canUseClipboardPaste(true, true)).isTrue()
        assertThat(VirtualDisplayTextInjector.canUseClipboardPaste(true, false)).isFalse()
        assertThat(VirtualDisplayTextInjector.canUseClipboardPaste(false, false)).isTrue()
    }

    @Test
    fun `focused set text can succeed without shell or clipboard`() = runBlocking {
        assertFocusedDelivery(ignoredSetText = false)
    }

    @Test
    fun `ignored focused set text falls through to shell before clipboard`() = runBlocking {
        assertFocusedDelivery(ignoredSetText = true)
    }

    private suspend fun assertFocusedDelivery(ignoredSetText: Boolean) {
        val events = mutableListOf<String>()
        val nodes = mockk<NodeActionPerformer>()
        var text = ""
        coEvery { nodes.performFocusNodeAt(10, 20) } coAnswers {
            events.add("focus")
            ActionResult.Success()
        }
        coEvery { nodes.readFocusedEditableText() } coAnswers {
            NodeActionPerformer.FocusedTextState(text)
        }
        coEvery { nodes.treeContainsText("hello") } coAnswers { text == "hello" }
        coEvery { nodes.treeSignature() } coAnswers { text.hashCode() }
        coEvery { nodes.performSetTextOnFocused("hello", clear = false) } coAnswers {
            events.add("set")
            if (!ignoredSetText) text = "hello"
            ActionResult.Success()
        }
        val shell: suspend (Array<String>) -> Int = { command ->
            events.add("shell")
            assertThat(command.toList()).containsExactly("input", "-d", "7", "text", "hello").inOrder()
            text = "hello"
            0
        }
        val injector = VirtualDisplayTextInjector(
            mockk(), { 7 }, nodes, mockk(),
            clipboardProvider = { error("Clipboard must remain untouched") },
            unattended = true,
            executeShellInput = shell,
        )

        val result = injector.inject(UIAction.InjectText("hello", focusX = 10, focusY = 20))
        assertThat(result).isInstanceOf(ActionResult.Success::class.java)
        val trail = (result as ActionResult.Success).message
        if (ignoredSetText) {
            assertThat(events).containsExactly("focus", "set", "shell").inOrder()
            assertThat(trail).contains("text:a11y-focused-set:failed_unchanged,text:shell")
        } else {
            assertThat(events).containsExactly("focus", "set").inOrder()
            assertThat(trail).contains("text:a11y-focused-set:verified")
        }
    }

    @Test
    fun `focused set text falls through when delivery is unconfirmed`() {
        for (verdict in VirtualDisplayTextInjector.Verdict.entries) {
            val accepted = verdict in setOf(
                VirtualDisplayTextInjector.Verdict.VERIFIED,
                VirtualDisplayTextInjector.Verdict.VERIFIED_PARTIAL,
            )
            assertThat(VirtualDisplayTextInjector.acceptFocusedSetText(verdict)).isEqualTo(accepted)
        }
    }

    @Test
    fun `unattended snapshot requires two matching readable values`() {
        val first = "original" to "text"
        assertThat(VirtualDisplayTextInjector.isStableSnapshot(first, first)).isTrue()
        assertThat(VirtualDisplayTextInjector.isStableSnapshot(first, "different" to "text")).isFalse()
        assertThat(VirtualDisplayTextInjector.isStableSnapshot(first, "original" to "different")).isFalse()
        assertThat(VirtualDisplayTextInjector.isStableSnapshot(first, null)).isFalse()
        assertThat(VirtualDisplayTextInjector.isStableSnapshot(null, null)).isFalse()
    }

    @Test
    fun `paste ownership requires the unique label and exact text`() {
        val description = mockk<ClipDescription>()
        val item = mockk<ClipData.Item>()
        val clip = mockk<ClipData>()
        every { description.label } returns "closepaw-paste-id"
        every { item.text } returns "ワイヤレス"
        every { clip.description } returns description
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item

        assertThat(VirtualDisplayTextInjector.isPasteClip(clip, "closepaw-paste-id", "ワイヤレス")).isTrue()
        assertThat(VirtualDisplayTextInjector.isPasteClip(clip, "other-label", "ワイヤレス")).isFalse()
        assertThat(VirtualDisplayTextInjector.isPasteClip(clip, "closepaw-paste-id", "other text")).isFalse()
        assertThat(VirtualDisplayTextInjector.isPasteClip(null, "closepaw-paste-id", "ワイヤレス")).isFalse()
    }
}
