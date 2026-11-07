package ai.closepaw.app

import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.draft.DraftPlatform
import ai.closepaw.draft.DraftSite
import ai.closepaw.draft.SaveDraftRequestParser

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

    /**
     * Reads and parses the file, turning every failure into a refusal. [Throwable] on purpose: the
     * input is hostile, and a [StackOverflowError] or [OutOfMemoryError] left to escape this worker
     * would kill the process, and with it every other run.
     */
    fun readRequest(site: DraftSite, read: () -> String): SaveDraftRequestParser.Parsed = try {
        SaveDraftRequestParser.parse(site, read())
    } catch (e: SecurityException) {
        SaveDraftRequestParser.Parsed.Bad("file-no-grant")
    } catch (e: Throwable) {
        SaveDraftRequestParser.Parsed.Bad("file-unreadable")
    }

    /** The broadcast's answer, whatever [decide] does. [finish] runs exactly once, last. */
    fun answer(
        decide: () -> Pair<Int, String>,
        reply: (Int, String) -> Unit,
        finish: () -> Unit,
    ) {
        try {
            val (code, data) = try {
                decide()
            } catch (e: Throwable) {
                ShellyBackgroundRun.RESULT_REJECTED to "START_FAILED"
            }
            reply(code, data)
        } catch (e: Throwable) {
            // Nothing left to tell the caller; only finish() must still happen.
        } finally {
            finish()
        }
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

    /** Fails closed: an unknown foreground app counts as Chrome. */
    fun chromeInFront(foregroundPackage: String?): Boolean =
        foregroundPackage == null || foregroundPackage == ChromeFlagDeepLink.CHROME_PACKAGE

    /** A window event that means the owner just opened Chrome on the main display. */
    fun ownerOpenedChrome(packageName: String?, isActivityWindow: Boolean, onMainDisplay: Boolean): Boolean =
        isActivityWindow && onMainDisplay && packageName == ChromeFlagDeepLink.CHROME_PACKAGE

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
