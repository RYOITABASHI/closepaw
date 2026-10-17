package ai.closepaw.agent.cognition.policy

/**
 * Deterministic evidence that items were actually added during an unattended run.
 *
 * The model's own "added 3 of 3" is not trusted (see [GoalCompletionContract]). Instead the
 * ledger counts confirmations ClosePaw itself observed on the device:
 *  - toast / notification accessibility events whose text is a confirmation
 *    ("Added to Smells Like 1991", "Saved to Your Library", "added to playlist" …), and
 *  - the same phrases found in a post-action screen capture (toast windows are part of the
 *    accessibility tree for a moment).
 *
 * Identical texts seen within [dedupeWindowMs] count once (a toast is usually reported several
 * times: event + tree capture). Pure Kotlin so it is unit-testable; [UnattendedEvidence] holds
 * the process-wide instance that the service feeds and the completion gate reads.
 */
class EvidenceLedger(
    private val clock: () -> Long = System::currentTimeMillis,
    private val dedupeWindowMs: Long = 3_000L,
) {
    companion object {
        /** Confirmation phrases. Case-insensitive, anywhere in the text. */
        val CONFIRMATION_RE: Regex = Regex(
            "(?:\\b(?:added to|saved to|added \\d+ (?:songs?|tracks?|items?)|" +
                "(?:song|track|item) added|has been added|were added|was added)\\b|追加しました|に追加)",
            RegexOption.IGNORE_CASE,
        )

        fun isConfirmation(text: String): Boolean =
            text.isNotBlank() && CONFIRMATION_RE.containsMatchIn(text)
    }

    private val lock = Any()
    private var lastText: String? = null
    private var lastAt: Long = Long.MIN_VALUE
    private var confirmations = 0
    private var sourcesSeen = 0

    /** Number of distinct confirmations observed since the last [reset]. */
    val count: Int get() = synchronized(lock) { confirmations }

    /** How many raw texts were offered (any source); lets callers tell "no evidence source" apart from "0 adds". */
    val samples: Int get() = synchronized(lock) { sourcesSeen }

    fun reset() = synchronized(lock) {
        lastText = null
        lastAt = Long.MIN_VALUE
        confirmations = 0
        sourcesSeen = 0
    }

    /** Offer any observed text. Returns true when it was counted as a new confirmation. */
    fun offer(text: String?): Boolean {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        synchronized(lock) {
            sourcesSeen++
            if (!isConfirmation(t)) return false
            val now = clock()
            if (t == lastText && now - lastAt <= dedupeWindowMs) return false
            lastText = t
            lastAt = now
            confirmations++
            return true
        }
    }
}

/** Process-wide ledger for the current unattended (Shelly) run. */
object UnattendedEvidence {
    val ledger = EvidenceLedger()

    @Volatile
    var active: Boolean = false
        private set

    /** Result of ClosePaw's own look at the target list, refreshed right before each completion decision. */
    @Volatile
    var verification: ListVerification? = null

    fun start() {
        ledger.reset()
        verification = null
        active = true
    }

    fun stop() {
        active = false
    }
}
