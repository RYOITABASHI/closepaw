package ai.closepaw.platform.virtualdisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHelperOwnershipTest {
    @Test fun staleOpenCannotPublishAfterNewerGenerationMinted() {
        val ownership = TaskHelperOwnership()
        val token1 = ownership.mint()
        val token2 = ownership.mint()

        ownership.markInstalled(token1)
        assertFalse(ownership.tryPublish(token1))
        assertFalse(ownership.isPublished(token1))

        ownership.markInstalled(token2)
        assertTrue(ownership.tryPublish(token2))
        assertTrue(ownership.isPublished(token2))
    }

    @Test fun detachInvalidatesAnInFlightOpen() {
        val ownership = TaskHelperOwnership()
        val token = ownership.mint()
        ownership.invalidate()

        ownership.markInstalled(token)
        assertFalse(ownership.tryPublish(token))
        assertFalse(ownership.isPublished(token))
    }

    @Test fun postedCloseClaimsItsOwnPairWhileItIsInstalled() {
        val ownership = TaskHelperOwnership()
        val token = ownership.mint()
        ownership.markInstalled(token)
        assertTrue(ownership.tryPublish(token))

        val postedClose = ownership.capture()
        assertTrue(ownership.claimInstalled(postedClose))
    }

    @Test fun postedCloseCannotClaimTheReplacementPair() {
        val ownership = TaskHelperOwnership()
        val token1 = ownership.mint()
        ownership.markInstalled(token1)
        assertTrue(ownership.tryPublish(token1))
        val postedClose = ownership.capture()

        val token2 = ownership.mint()
        ownership.markRetired(token1.generation)
        ownership.markInstalled(token2)
        assertTrue(ownership.tryPublish(token2))

        assertFalse(ownership.claimInstalled(postedClose))
        ownership.markRetired(postedClose.generation)
        assertTrue(ownership.isPublished(token2))
        assertTrue(ownership.claimInstalled(token2))
    }

    @Test fun publishRequiresTheInstalledPair() {
        val ownership = TaskHelperOwnership()
        val token = ownership.mint()
        assertFalse(ownership.tryPublish(token))

        ownership.markInstalled(token)
        assertTrue(ownership.tryPublish(token))
    }

    @Test fun stopInvalidationBlocksInFlightOpenButCloseStillClaimsItsPair() {
        val ownership = TaskHelperOwnership()
        val openToken = ownership.mint()
        // stop() captures the close token, then invalidates, then posts the close.
        val closeToken = ownership.capture()
        ownership.invalidate()

        // The in-flight open completes after invalidation: it installs its
        // pair but cannot publish it, so it retires the pair itself.
        ownership.markInstalled(openToken)
        assertFalse(ownership.tryPublish(openToken))
        assertFalse(ownership.isPublished(openToken))
        // The posted close still owns the installed pair: the claim is by
        // generation, and invalidation revokes publication, not the close.
        assertTrue(ownership.claimInstalled(closeToken))
    }

    @Test fun invalidateIfCurrentDoesNotInvalidateAReplacementGeneration() {
        val ownership = TaskHelperOwnership()
        val ending = ownership.mint()
        // The binder-death callback captures the ending session's token,
        // then pauses; the Broken restart mints the replacement token
        // before the callback resumes.
        val captured = ownership.capture()
        val replacement = ownership.mint()

        assertFalse(ownership.invalidateIfCurrent(captured))

        // The replacement's in-flight open is untouched: it installs and
        // publishes normally.
        ownership.markInstalled(replacement)
        assertTrue(ownership.tryPublish(replacement))
        assertTrue(ownership.isPublished(replacement))
        assertTrue(ownership.isCurrent(replacement))
        assertFalse(ownership.isCurrent(ending))
    }

    @Test fun invalidateIfCurrentAppliesWhileTheTokenIsCurrent() {
        val ownership = TaskHelperOwnership()
        val openToken = ownership.mint()
        val captured = ownership.capture()

        assertTrue(ownership.invalidateIfCurrent(captured))

        // The in-flight open of the ending session is blocked, exactly as
        // with the unconditional invalidate.
        ownership.markInstalled(openToken)
        assertFalse(ownership.tryPublish(openToken))
        assertFalse(ownership.isPublished(openToken))
        // The close carrying the captured token still claims its pair.
        assertTrue(ownership.claimInstalled(captured))
        // A second conditional invalidation with the now-stale token is
        // a no-op.
        assertFalse(ownership.invalidateIfCurrent(captured))
    }

    @Test fun transitionBoundCloseRetiresOnlyTheEndingPairAfterARestartMint() {
        val ownership = TaskHelperOwnership()
        val ending = ownership.mint()
        ownership.markInstalled(ending)
        assertTrue(ownership.tryPublish(ending))

        // Binder death: the callback captures the ending session's token in
        // the same synchronous block as the Broken transition, then is
        // paused before its deferred close executes.
        val transitionToken = ownership.capture()

        // A Broken restart mints the replacement generation meanwhile.
        val replacement = ownership.mint()

        // The deferred close executes carrying the transition-bound token:
        // the conditional invalidation must not touch the replacement, and
        // the close claims only the ending session's installed pair, which
        // it retires.
        assertFalse(ownership.invalidateIfCurrent(transitionToken))
        assertTrue(ownership.claimInstalled(transitionToken))
        ownership.markRetired(transitionToken.generation)

        // The replacement's open proceeds untouched: it installs and
        // publishes normally.
        ownership.markInstalled(replacement)
        assertTrue(ownership.tryPublish(replacement))
        assertTrue(ownership.isPublished(replacement))
        assertTrue(ownership.isCurrent(replacement))
    }

    @Test fun transitionCaptureAfterRestartMintNamesTheNewGeneration() {
        val ownership = TaskHelperOwnership()
        val ending = ownership.mint()
        ownership.markInstalled(ending)
        assertTrue(ownership.tryPublish(ending))

        // The Broken restart's mint lands entirely BEFORE the binder-death
        // transition: the replacement session installs and publishes.
        val replacement = ownership.mint()
        ownership.markInstalled(replacement)
        assertTrue(ownership.tryPublish(replacement))

        // Binder death: the transition and the capture are one atomic step,
        // so the token names the replacement — the session the transition
        // breaks — and the deferred close retires its pair.
        val transitionToken = ownership.captureAfterTransition { }
        assertTrue(ownership.isCurrent(transitionToken))
        assertTrue(ownership.claimInstalled(transitionToken))
        assertTrue(ownership.invalidateIfCurrent(transitionToken))
        assertFalse(ownership.isPublished(replacement))
    }

    @Test fun transitionCaptureBeforeRestartMintNamesTheEndingGeneration() {
        val ownership = TaskHelperOwnership()
        val ending = ownership.mint()
        ownership.markInstalled(ending)
        assertTrue(ownership.tryPublish(ending))

        // Binder death: the transition and the capture complete atomically
        // BEFORE any restart mint.
        val transitionToken = ownership.captureAfterTransition { }

        // The Broken restart mints afterwards: the deferred close must not
        // touch the replacement, and still claims the ending pair.
        val replacement = ownership.mint()
        assertFalse(ownership.invalidateIfCurrent(transitionToken))
        assertTrue(ownership.claimInstalled(transitionToken))
        ownership.markRetired(transitionToken.generation)

        ownership.markInstalled(replacement)
        assertTrue(ownership.tryPublish(replacement))
        assertTrue(ownership.isPublished(replacement))
        assertTrue(ownership.isCurrent(replacement))
    }

    @Test fun transitionCaptureRunsTheTransitionBeforeCapturing() {
        val ownership = TaskHelperOwnership()
        val before = ownership.mint()

        // The transition's own effect must be reflected in the captured
        // token: the capture happens after the transition, inside the
        // same monitor hold.
        val transitionToken = ownership.captureAfterTransition { ownership.invalidate() }
        assertTrue(ownership.isCurrent(transitionToken))
        assertFalse(ownership.isCurrent(before))
    }
}
