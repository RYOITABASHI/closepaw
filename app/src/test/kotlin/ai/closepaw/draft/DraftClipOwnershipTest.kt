package ai.closepaw.draft

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DraftClipOwnershipTest {
    private val clip = DraftClipOwnership()

    private val html = "<p>Hello world</p><p>Second paragraph</p>"
    private val plain = "Hello world\n\nSecond paragraph\n\n"
    private val label = "closepaw-draft-run"

    @Test
    fun `nothing written means nothing is cleared`() {
        assertThat(clip.releaseForClear(label, 1, plain, html)).isFalse()
    }

    @Test
    fun `a failed write records nothing, so the owner's clip is never cleared`() {
        // setClipboard threw: recordWrite was never called.
        assertThat(clip.releaseForClear("owner", 1, "owner's text", null)).isFalse()
    }

    @Test
    fun `closepaw's own write is cleared only with its label and its exact content`() {
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(label, 1, plain, html)).isTrue()
    }

    @Test
    fun `the exact plain text alone is enough under the run's label`() {
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(label, 1, plain, null)).isTrue()
    }

    @Test
    fun `text that differs only in whitespace is not the run's`() {
        // Gate fix round 3, finding 1: no normalization ("A B" is not "AB").
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(label, 1, "Hello worldSecond paragraph", null)).isFalse()
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(label, 1, "Hello world Second paragraph", null)).isFalse()
    }

    @Test
    fun `the run's label on someone else's content is not the run's`() {
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(label, 1, "something the owner copied", null)).isFalse()
    }

    @Test
    fun `the run's content under another label is not closepaw's own write`() {
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear("owner", 1, plain, html)).isFalse()
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear("", 1, plain, html)).isFalse()
    }

    @Test
    fun `a multi-item clip is never cleared`() {
        // Gate fix round 3, finding 1: only the first item could be inspected.
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(label, 2, plain, html)).isFalse()
    }

    @Test
    fun `an unreadable clipboard is never cleared`() {
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(null, 0, null, null)).isFalse()
    }

    @Test
    fun `an empty text never matches`() {
        clip.recordWrite(label, "<p> </p>", "")
        assertThat(clip.releaseForClear(label, 1, "", null)).isFalse()
    }

    @Test
    fun `ownership is answered once`() {
        clip.recordWrite(label, html, plain)
        assertThat(clip.releaseForClear(label, 1, plain, html)).isTrue()
        assertThat(clip.releaseForClear(label, 1, plain, html)).isFalse()
    }
}
