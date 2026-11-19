package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
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
}
