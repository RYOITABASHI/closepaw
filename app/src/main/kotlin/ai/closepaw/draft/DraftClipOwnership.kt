package ai.closepaw.draft

/**
 * Whether the clip on the clipboard right now is provably the draft run's own (gate fix round 2,
 * finding 1; tightened in gate fix round 3, finding 1).
 *
 * Only ClosePaw's OWN writes ([ChromeDraftBrowser.setClipboard]) are recorded: they carry a label
 * unique to the run and content ClosePaw chose byte for byte. The page's own clipboard write is
 * never recorded and never cleaned up: its label is the browser's, and its text cannot be told
 * apart from an identical copy the owner made, so no read-back can prove it is the run's.
 *
 * A write is recorded only AFTER it succeeded ([recordWrite]). At cleanup the clip on the clipboard
 * must be a single item, under the run's exact label, whose HTML or plain text is EXACTLY what was
 * written: no normalization. Anything else (unreadable, several items, another label, other
 * content) is not the run's and is left alone. The worst case is that the run's article stays on
 * the clipboard (marked sensitive on API 33+), never that the owner's clip is erased.
 */
internal class DraftClipOwnership {
    private class Write(val label: String, val html: String, val plain: String)

    private val writes = ArrayList<Write>()

    /** A ClosePaw write that SUCCEEDED, under the run's unique [label], with exactly [plain] and [html]. */
    @Synchronized
    fun recordWrite(label: String, html: String, plain: String) {
        writes += Write(label, html, plain)
    }

    /**
     * True when the clip currently on the clipboard ([label], [itemCount] and its only item's
     * [text] / [htmlText], as read back; null when unreadable) is exactly one of the run's recorded
     * writes. Answered once: the records are dropped.
     */
    @Synchronized
    fun releaseForClear(label: String?, itemCount: Int, text: String?, htmlText: String?): Boolean {
        val recorded = writes.toList()
        writes.clear()
        if (recorded.isEmpty() || label == null || itemCount != 1) return false
        if (text == null && htmlText == null) return false
        return recorded.any { w ->
            w.label == label &&
                ((htmlText != null && htmlText == w.html) || (text != null && text.isNotEmpty() && text == w.plain))
        }
    }
}
