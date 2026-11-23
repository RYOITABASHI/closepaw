package ai.closepaw.platform.virtualdisplay

/**
 * Tracks ownership tokens captured when work is posted. Publication is a
 * compare-and-set against that token. Closes act on the installed pair by
 * generation because the session holds at most one pair, installs it under
 * its own generation, and retires any predecessor before installing it.
 */
internal class TaskHelperOwnership {
    /** Immutable ownership token captured when deferred work is posted. */
    class Token internal constructor(val generation: Int, val epoch: Int)

    private var generation = 0
    private var epoch = 0
    private var installed = 0
    private var published = 0

    /** Advance to a fresh generation and invalidate earlier tokens for publication. */
    @Synchronized
    fun mint(): Token {
        generation += 1
        epoch += 1
        return Token(generation, epoch)
    }

    /** Capture the current token before posting work. */
    @Synchronized
    fun capture(): Token = Token(generation, epoch)

    /**
     * Runs [transition] and captures the token that is current immediately
     * after it, atomically under the ownership monitor. [mint] and
     * [tryPublish] take the same monitor, so neither can interleave
     * between the transition and the capture: the returned token names
     * exactly the generation the transition just ended. A mint that
     * completed before this call is reflected in the token; a mint that
     * starts after this call cannot be named by it.
     */
    @Synchronized
    fun captureAfterTransition(transition: () -> Unit): Token {
        transition()
        return Token(generation, epoch)
    }

    /** Check that no mint or detach has happened since capture. */
    @Synchronized
    fun isCurrent(token: Token): Boolean = token.generation == generation && token.epoch == epoch

    @Synchronized
    fun installedGeneration(): Int = installed

    /** Record that the session now holds [token]'s pair. */
    @Synchronized
    fun markInstalled(token: Token) {
        installed = token.generation
    }

    /** Publish the current installed pair. On failure the caller retires its pair. */
    @Synchronized
    fun tryPublish(token: Token): Boolean {
        if (!isCurrent(token) || installed != token.generation) return false
        published = token.generation
        return true
    }

    /** Unpublish immediately and invalidate in-flight opens. */
    @Synchronized
    fun invalidate() {
        epoch += 1
        published = 0
    }

    /**
     * Invalidate only while [token] is still the current token. A caller
     * that captured its token and was paused while a replacement session
     * minted (or another invalidation already landed) must not invalidate
     * state that no longer belongs to its session. Returns true when the
     * invalidation was applied.
     */
    @Synchronized
    fun invalidateIfCurrent(token: Token): Boolean {
        if (!isCurrent(token)) return false
        epoch += 1
        published = 0
        return true
    }

    @Synchronized
    fun isPublished(token: Token): Boolean = token.generation != 0 && published == token.generation

    /** Check whether a close carrying [token] owns the installed pair. */
    @Synchronized
    fun claimInstalled(token: Token): Boolean = token.generation != 0 && installed == token.generation

    /** Clear the install and publish marks for [generation]. */
    @Synchronized
    fun markRetired(generation: Int) {
        if (installed == generation) installed = 0
        if (published == generation) published = 0
    }
}
