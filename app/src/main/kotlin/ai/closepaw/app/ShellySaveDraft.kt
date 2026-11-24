package ai.closepaw.app

import ai.closepaw.browser.setup.ChromeFlagDeepLink
import ai.closepaw.draft.DraftPlatform
import ai.closepaw.draft.DraftSite
import ai.closepaw.draft.SaveDraftRequestParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure rules for the draft entry ([ShellySaveDraftReceiver]). Result codes follow
 * [ShellyBackgroundRun]: 1 started on the virtual display, 10 rejected, 11/12/15/16 as its
 * [ShellyBackgroundRun.Block], plus [RESULT_DEVICE_LOCKED] and [RESULT_CHROME_IN_USE].
 *
 * The result correlation is mandatory here: a draft run with nobody to tell is refused, because a
 * silent outcome is what `uncertain` exists to prevent.
 *
 * The caller also states the SHA-256 of the request file. The file sits behind a URI that could be
 * rewritten between Shelly's approval and this read, so the hash, carried in the broadcast that
 * proves the caller, binds what was approved to what runs. Everything the run uses, `image_hosts`
 * included, comes only from bytes that passed it.
 *
 * The ordered broadcast is answered within [ANSWER_CAP_MS], and "started" is only ever sent for a
 * run that has started. So reading and parsing happen before the answer, under a hard cap: the
 * alternative, answering "started" first, would turn every refusal into a later callback and make
 * the code mean nothing. See [AnswerWindow].
 */
object ShellySaveDraft {
    const val ACTION_SAVE_DRAFT = "ai.closepaw.action.SHELLY_SAVE_DRAFT"
    const val EXTRA_PLATFORM = "platform"
    const val EXTRA_SUBSTACK_SUBDOMAIN = "substack_subdomain"
    const val EXTRA_REQUEST_SHA256 = "shelly_request_sha256"

    const val RESULT_DEVICE_LOCKED = 17
    const val RESULT_CHROME_IN_USE = 18

    /** The ordered answer's data when the cap is hit. Nothing has started. */
    const val REASON_TOO_SLOW = "request-too-slow"
    const val REASON_HASH_MISMATCH = "request-hash-mismatch"

    /**
     * From the broadcast's arrival to the answer. It must stay well under both the caller's wait
     * (5 s) and the system's limit for a foreground broadcast (10 s), so that a run can never start
     * after the caller has given up on hearing that it did.
     */
    const val ANSWER_CAP_MS = 3_000L

    private const val MAX_REMEMBERED_REQUESTS = 32
    private val SHA256_RE = Regex("^[0-9a-f]{64}$")

    /**
     * Shelly's FileProvider (`${packageName}.shelly.fileprovider`) and the shape of the one URI
     * its approved draft send produces: `device_share/draft-<32 hex>/request.json`, a snapshot in
     * a fresh random directory, granted by Shelly to ClosePaw only, for that send only, and revoked
     * after (Shelly ClosePawDrafts.serve / newSnapshotDir / SNAPSHOT_DIR_RE, shipped in build 3015).
     */
    const val SHELLY_FILE_AUTHORITY = "${ShellyTrustedCaller.SHELLY_PACKAGE}.shelly.fileprovider"
    private const val SNAPSHOT_ROOT = "device_share"
    private const val SNAPSHOT_FILE = "request.json"
    private val SNAPSHOT_DIR_RE = Regex("^draft-[0-9a-f]{32}$")

    /** The exact URI shape of an approved draft snapshot (see [SHELLY_FILE_AUTHORITY]). */
    fun isDraftSnapshotUri(authority: String?, pathSegments: List<String>): Boolean =
        authority == SHELLY_FILE_AUTHORITY &&
            pathSegments.size == 3 &&
            pathSegments[0] == SNAPSHOT_ROOT &&
            SNAPSHOT_DIR_RE.matches(pathSegments[1]) &&
            pathSegments[2] == SNAPSHOT_FILE

    /**
     * Sender proof below API 34, where the broadcaster cannot be named (gate fix round 1, finding
     * 4). The caller token proves only that some Shelly-made PendingIntent was attached, and such
     * tokens can leak. What a token holder cannot produce is a READABLE approved-draft snapshot:
     * - its URI must have the snapshot shape. Shelly's other `device_share` files (the `share`
     *   command, which does grant third parties) live in `<reqId>` directories, never `draft-*`;
     * - the broadcast must carry no URI grant of its own (flags or ClipData). Shelly's draft send
     *   grants with Context.grantUriPermission before sending and sets none, so the only way in is
     *   Shelly's own per-send grant to ClosePaw, which no third party holds or can pass on;
     * - the read then has to succeed and match the stated SHA-256, which needs those exact bytes.
     * A third party would have to guess the 128-bit random directory of a send in flight and its
     * content hash. On API 34+ the named sender already decides, so the shape is not required there
     * (the device's verified behavior stays exactly as it was).
     */
    fun legacySenderProofOk(
        sdkInt: Int,
        authority: String?,
        pathSegments: List<String>,
        carriesUriGrant: Boolean,
        hasClipData: Boolean,
    ): Boolean {
        if (sdkInt >= 34) return true
        if (carriesUriGrant || hasClipData) return false
        return isDraftSnapshotUri(authority, pathSegments)
    }

    data class Target(
        val site: DraftSite,
        val correlation: ShellyBackgroundRun.ResultCorrelation,
        val requestSha256: String,
    )

    /** Trust and shape gate. Null means: ignore the broadcast. */
    fun parseTarget(
        action: String?,
        callerVerified: Boolean,
        platformExtra: String?,
        subdomainExtra: String?,
        reqIdExtra: String?,
        nonceExtra: String?,
        sha256Extra: String?,
    ): Target? {
        if (action != ACTION_SAVE_DRAFT || !callerVerified) return null
        val platform = DraftPlatform.fromWire(platformExtra) ?: return null
        val site = DraftSite.of(platform, subdomainExtra) ?: return null
        val correlation = ShellyBackgroundRun.parseResultCorrelation(reqIdExtra, nonceExtra) ?: return null
        val sha256 = sha256Extra?.takeIf { SHA256_RE.matches(it) } ?: return null
        return Target(site, correlation, sha256)
    }

    /**
     * Reads and parses the file, turning every failure into a refusal. [Throwable] on purpose: the
     * input is hostile, and a [StackOverflowError] or [OutOfMemoryError] left to escape this worker
     * would kill the process, and with it every other run.
     *
     * The hash is checked on the exact bytes read, before any of them is decoded or parsed, and
     * only those bytes are parsed: there is no second read for the file to change under.
     */
    fun readRequest(
        site: DraftSite,
        expectedSha256: String,
        read: () -> ByteArray,
    ): SaveDraftRequestParser.Parsed = try {
        val bytes = read()
        if (!sha256Matches(bytes, expectedSha256)) {
            SaveDraftRequestParser.Parsed.Bad(REASON_HASH_MISMATCH)
        } else {
            SaveDraftRequestParser.parse(site, decodeUtf8(bytes))
        }
    } catch (e: SecurityException) {
        SaveDraftRequestParser.Parsed.Bad("file-no-grant")
    } catch (e: CharacterCodingException) {
        SaveDraftRequestParser.Parsed.Bad("file-not-utf8")
    } catch (e: Throwable) {
        SaveDraftRequestParser.Parsed.Bad("file-unreadable")
    }

    private fun sha256Matches(bytes: ByteArray, expected: String): Boolean {
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())
    }

    /** Strict: a lossy decode would let two different byte strings parse the same. */
    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()

    /** Reads at most [maxBytes], checking [expired] between chunks. Closing is the caller's. */
    fun readLimited(input: InputStream, maxBytes: Int, expired: () -> Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (expired()) throw IOException("read took too long")
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw IOException("file too large")
        }
    }

    /**
     * Who answers the broadcast: exactly one of the worker and the watchdog. A read blocked inside
     * a provider cannot be interrupted from its own thread, so the cap is enforced from outside by
     * a timer, and a decision that arrives late is refused rather than started. Refusing is the
     * safe direction: the caller then knows nothing ran, and can ask again.
     */
    class AnswerWindow(private val capMs: Long, private val now: () -> Long) {
        enum class Claim { IN_TIME, LATE, ALREADY_ANSWERED }

        private val openedAt = now()
        private val answered = AtomicBoolean(false)

        fun isExpired(): Boolean = now() - openedAt >= capMs

        fun claim(): Claim = when {
            !answered.compareAndSet(false, true) -> Claim.ALREADY_ANSWERED
            isExpired() -> Claim.LATE
            else -> Claim.IN_TIME
        }
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

    /**
     * A known main-display task takes precedence over the window, which can belong to the run
     * display. With the helper route enabled, an unknown task blocks admission. Otherwise the
     * window can decide and an unknown window also blocks admission.
     */
    fun chromeInFront(
        foregroundPackage: String?,
        frontTaskIsChrome: Boolean? = null,
        requireTaskObservation: Boolean = false,
    ): Boolean = frontTaskIsChrome ?: (
        requireTaskObservation || foregroundPackage == null ||
            foregroundPackage == ChromeFlagDeepLink.CHROME_PACKAGE
    )

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
