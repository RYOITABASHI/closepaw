package ai.closepaw.app

import ai.closepaw.draft.DraftPlatform
import ai.closepaw.draft.DraftSite

/**
 * Pure rules for the draft entry ([ShellySaveDraftReceiver]). Result codes follow
 * [ShellyBackgroundRun]: 1 started on the virtual display, 10 rejected, 11/12/15/16 as its
 * [ShellyBackgroundRun.Block], plus [RESULT_DEVICE_LOCKED] and [RESULT_CHROME_IN_USE].
 *
 * The result correlation is mandatory here: a draft run with nobody to tell is refused, because a
 * silent outcome is what `uncertain` exists to prevent.
 */
object ShellySaveDraft {
    const val ACTION_SAVE_DRAFT = "ai.closepaw.action.SHELLY_SAVE_DRAFT"
    const val EXTRA_PLATFORM = "platform"
    const val EXTRA_SUBSTACK_SUBDOMAIN = "substack_subdomain"

    const val RESULT_DEVICE_LOCKED = 17
    const val RESULT_CHROME_IN_USE = 18

    private const val MAX_REMEMBERED_REQUESTS = 32

    data class Target(
        val site: DraftSite,
        val correlation: ShellyBackgroundRun.ResultCorrelation,
    )

    /** Trust and shape gate. Null means: ignore the broadcast. */
    fun parseTarget(
        action: String?,
        callerVerified: Boolean,
        platformExtra: String?,
        subdomainExtra: String?,
        reqIdExtra: String?,
        nonceExtra: String?,
    ): Target? {
        if (action != ACTION_SAVE_DRAFT || !callerVerified) return null
        val platform = DraftPlatform.fromWire(platformExtra) ?: return null
        val site = DraftSite.of(platform, subdomainExtra) ?: return null
        val correlation = ShellyBackgroundRun.parseResultCorrelation(reqIdExtra, nonceExtra) ?: return null
        return Target(site, correlation)
    }

    /** The result code that refuses this run, or null to start it. */
    fun refusal(
        accessibilityConnected: Boolean,
        onboardingCompleted: Boolean,
        sessionBusy: Boolean,
        virtualDisplayReady: Boolean,
        deviceLocked: Boolean,
        chromeInFront: Boolean,
    ): Int? = when {
        !accessibilityConnected -> ShellyBackgroundRun.Block.ACCESSIBILITY_OFF.resultCode
        !onboardingCompleted -> ShellyBackgroundRun.Block.ONBOARDING_REQUIRED.resultCode
        deviceLocked -> RESULT_DEVICE_LOCKED
        sessionBusy -> ShellyBackgroundRun.Block.BUSY.resultCode
        !virtualDisplayReady -> ShellyBackgroundRun.Block.VIRTUAL_DISPLAY_UNAVAILABLE.resultCode
        chromeInFront -> RESULT_CHROME_IN_USE
        else -> null
    }

    /** Remembers request ids, so one approved request starts at most one run. */
    class ReplayGuard {
        private val seen = LinkedHashSet<String>()

        @Synchronized
        fun firstUse(reqId: String): Boolean {
            if (!seen.add(reqId)) return false
            while (seen.size > MAX_REMEMBERED_REQUESTS) seen.remove(seen.first())
            return true
        }
    }
}
