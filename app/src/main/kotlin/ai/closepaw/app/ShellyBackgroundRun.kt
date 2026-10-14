package ai.closepaw.app

import ai.closepaw.protocol.AgentEvent
import ai.closepaw.protocol.ActionExecuted
import ai.closepaw.protocol.ApprovalRequired
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.protocol.ScreenCaptured
import ai.closepaw.protocol.SessionCompleted
import ai.closepaw.protocol.SessionError
import ai.closepaw.protocol.SessionResumed
import ai.closepaw.protocol.SessionTakeover
import ai.closepaw.protocol.TaskCompleted
import ai.closepaw.protocol.TaskOutcome
import ai.closepaw.protocol.TurnPhaseChanged

/**
 * Background ("headless") runs requested by a verified Shelly caller.
 *
 * Entry point: [ShellyGoalReceiver] (explicit broadcast). Unlike the MainActivity hand-off, this
 * path never opens ClosePaw's UI: the goal runs on the Shizuku virtual display and the only
 * visible indicators are the Smart Capsule overlay and an ongoing notification
 * ([ShellyRunNotifier]). Everything in this file is pure logic so it can be unit tested.
 */
object ShellyBackgroundRun {
    const val ACTION_RUN_GOAL = "ai.closepaw.action.SHELLY_RUN_GOAL"
    const val EXTRA_GOAL = "goal"

    /**
     * Opt-in: when the virtual display is unavailable, run on the main display instead of
     * refusing. Default false — the run would take over the user's screen.
     */
    const val EXTRA_ALLOW_MAIN_DISPLAY = "allow_main_display"

    const val MAX_GOAL_CHARS = 4_000

    // Ordered-broadcast result codes (Shelly should send with initialCode = 0, so 0 means
    // "no receiver answered": ClosePaw missing, too old, or stopped).
    const val RESULT_STARTED_VIRTUAL_DISPLAY = 1
    const val RESULT_STARTED_MAIN_DISPLAY = 2
    const val RESULT_REJECTED = 10

    enum class Block(val resultCode: Int) {
        ACCESSIBILITY_OFF(11),
        ONBOARDING_REQUIRED(12),
        OVERLAY_PERMISSION_MISSING(13),
        CREDENTIALS_MISSING(14),
        BUSY(15),
        VIRTUAL_DISPLAY_UNAVAILABLE(16),
    }

    sealed interface Decision {
        data class Start(val platformMode: PlatformMode) : Decision
        data class Blocked(val block: Block) : Decision
    }

    data class Request(val goal: String, val allowMainDisplay: Boolean)

    /**
     * Trust gate for the exported receiver. Returns null (= ignore the broadcast, do nothing
     * visible) unless the action matches AND the caller token verified AND a goal is present.
     */
    fun parseRequest(
        action: String?,
        goalExtra: String?,
        callerVerified: Boolean,
        allowMainDisplay: Boolean,
    ): Request? {
        if (action != ACTION_RUN_GOAL) return null
        if (!callerVerified) return null
        val goal = goalExtra?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Request(goal.take(MAX_GOAL_CHARS), allowMainDisplay)
    }

    /** Preconditions, checked synchronously before anything becomes visible. */
    fun precheck(
        accessibilityConnected: Boolean,
        onboardingCompleted: Boolean,
        canDrawOverlays: Boolean,
        credentialsMissing: Boolean,
        sessionBusy: Boolean,
        virtualDisplayReady: Boolean,
        allowMainDisplay: Boolean,
    ): Decision = when {
        !accessibilityConnected -> Decision.Blocked(Block.ACCESSIBILITY_OFF)
        !onboardingCompleted -> Decision.Blocked(Block.ONBOARDING_REQUIRED)
        !canDrawOverlays -> Decision.Blocked(Block.OVERLAY_PERMISSION_MISSING)
        credentialsMissing -> Decision.Blocked(Block.CREDENTIALS_MISSING)
        sessionBusy -> Decision.Blocked(Block.BUSY)
        virtualDisplayReady -> Decision.Start(PlatformMode.VIRTUAL_DISPLAY)
        allowMainDisplay -> Decision.Start(PlatformMode.ACCESSIBILITY)
        else -> Decision.Blocked(Block.VIRTUAL_DISPLAY_UNAVAILABLE)
    }
}

/** What the user should be told about a running Shelly background run. */
sealed interface ShellyRunState {
    /** Agent is working. [onMainDisplay] = it is driving the real screen (do not touch). */
    data class Working(val onMainDisplay: Boolean, val appPackages: List<String>) : ShellyRunState

    data class WaitingForApproval(
        val onMainDisplay: Boolean,
        val callId: String,
        val packageName: String,
        val description: String,
    ) : ShellyRunState

    /** Paused because the user opened the app the agent was operating ([packageName]), or took over. */
    data class Paused(val packageName: String?) : ShellyRunState

    data class Finished(val kind: Kind, val message: String?) : ShellyRunState {
        enum class Kind { DONE, STOPPED, FAILED }
    }
}

/**
 * Tracks one Shelly background run (one session). Pure state machine; the Android side feeds it
 * agent events and accessibility window events and renders [state].
 */
class ShellyRunTracker(
    val sessionId: String,
    val onMainDisplay: Boolean,
    private val ignoredPackages: Set<String>,
) {
    private val vdPackages = LinkedHashSet<String>()

    var state: ShellyRunState = ShellyRunState.Working(onMainDisplay, emptyList())
        private set

    val isFinished: Boolean get() = state is ShellyRunState.Finished

    /** Apps the agent is (or recently was) operating on the virtual display, most recent last. */
    val operatedPackages: List<String> get() = vdPackages.toList()

    private fun rememberPackage(pkg: String?): Boolean {
        if (onMainDisplay || pkg.isNullOrBlank() || pkg in ignoredPackages) return false
        if (vdPackages.lastOrNull() == pkg) return false
        vdPackages.remove(pkg)
        vdPackages.add(pkg)
        while (vdPackages.size > MAX_TRACKED_APPS) vdPackages.remove(vdPackages.first())
        return true
    }

    private fun refreshWorking(): Boolean {
        val current = state
        if (current is ShellyRunState.Working) {
            val next = current.copy(appPackages = operatedPackages)
            if (next != current) {
                state = next
                return true
            }
        }
        return false
    }

    /** A window appeared on the virtual display (display id != 0). Returns true if [state] changed. */
    fun onVirtualDisplayWindow(pkg: String?): Boolean {
        if (isFinished) return false
        return rememberPackage(pkg) && refreshWorking()
    }

    /**
     * A window appeared on the main display. If it belongs to an app the agent is operating on the
     * virtual display, the user just pulled that app's task off the VD: pause instead of fighting
     * the user. Returns the conflicting package (caller must request a takeover/pause), else null.
     */
    fun onMainDisplayWindow(pkg: String?): String? {
        if (onMainDisplay || pkg.isNullOrBlank() || pkg in ignoredPackages) return null
        if (state !is ShellyRunState.Working) return null
        if (pkg !in vdPackages) return null
        state = ShellyRunState.Paused(pkg)
        return pkg
    }

    /** The user answered the pending approval (capsule or notification). */
    fun onApprovalResolved(callId: String): Boolean {
        val current = state as? ShellyRunState.WaitingForApproval ?: return false
        if (current.callId != callId) return false
        state = ShellyRunState.Working(onMainDisplay, operatedPackages)
        return true
    }

    /** Returns true if [state] changed. */
    fun onEvent(event: AgentEvent): Boolean {
        if (isFinished) return false
        val before = state
        when (event) {
            is ScreenCaptured -> {
                rememberPackage(event.packageName)
                refreshWorking()
            }
            is ApprovalRequired -> {
                rememberPackage(event.details.packageName)
                state = ShellyRunState.WaitingForApproval(
                    onMainDisplay = onMainDisplay,
                    callId = event.details.callId,
                    packageName = event.details.packageName,
                    description = event.details.description.ifBlank { event.description },
                )
            }
            is ActionExecuted, is TurnPhaseChanged -> {
                if (state is ShellyRunState.WaitingForApproval) {
                    state = ShellyRunState.Working(onMainDisplay, operatedPackages)
                }
            }
            is SessionTakeover -> {
                if (state !is ShellyRunState.Paused) state = ShellyRunState.Paused(null)
            }
            is SessionResumed -> {
                state = ShellyRunState.Working(onMainDisplay, operatedPackages)
            }
            is TaskCompleted -> {
                val kind = when (event.outcome) {
                    TaskOutcome.GOAL_ACHIEVED -> ShellyRunState.Finished.Kind.DONE
                    TaskOutcome.USER_STOPPED -> ShellyRunState.Finished.Kind.STOPPED
                    TaskOutcome.TASK_IMPOSSIBLE, TaskOutcome.ERROR -> ShellyRunState.Finished.Kind.FAILED
                }
                state = ShellyRunState.Finished(kind, event.result?.takeIf { it.isNotBlank() })
            }
            is SessionError -> {
                state = ShellyRunState.Finished(ShellyRunState.Finished.Kind.FAILED, event.message)
            }
            is SessionCompleted -> {
                state = ShellyRunState.Finished(ShellyRunState.Finished.Kind.STOPPED, null)
            }
            else -> Unit
        }
        return state != before
    }

    /** Ends the run from outside the event stream (e.g. replaced by another session). */
    fun finish(kind: ShellyRunState.Finished.Kind, message: String? = null) {
        if (!isFinished) state = ShellyRunState.Finished(kind, message)
    }

    companion object {
        private const val MAX_TRACKED_APPS = 3
    }
}

/** User-facing text for [ShellyRunState] (en / ja; the rest of ClosePaw is English-only). */
class ShellyRunText(private val japanese: Boolean) {
    data class Content(val title: String, val body: String)

    private fun t(en: String, ja: String) = if (japanese) ja else en

    private fun quote(label: String) = if (japanese) "「$label」" else "\"$label\""

    fun render(state: ShellyRunState, labelOf: (String) -> String): Content = when (state) {
        is ShellyRunState.Working ->
            if (state.onMainDisplay) {
                Content(
                    t(
                        "ClosePaw is controlling the screen — please don't touch",
                        "ClosePawが画面を操作中 — 画面に触れないでください",
                    ),
                    t(
                        "The virtual display is unavailable, so ClosePaw is running on your main screen. Tap Stop to end it.",
                        "仮想ディスプレイが使えないため、メイン画面で実行しています。終了するには「停止」を押してください。",
                    ),
                )
            } else {
                val apps = state.appPackages.map(labelOf).map(::quote)
                Content(
                    t(
                        "ClosePaw is working in the background — you can keep using your phone",
                        "ClosePawがバックグラウンドで作業中 — スマホはそのまま使えます",
                    ),
                    if (apps.isEmpty()) {
                        t(
                            "Don't open the app ClosePaw is operating until it finishes.",
                            "完了するまで、ClosePawが操作中のアプリは開かないでください。",
                        )
                    } else {
                        t(
                            "Don't open ${apps.joinToString(", ")} until ClosePaw finishes.",
                            "完了するまで${apps.joinToString("、")}は開かないでください。",
                        )
                    },
                )
            }
        is ShellyRunState.WaitingForApproval -> {
            val app = quote(labelOf(state.packageName))
            Content(
                t("Waiting for your approval", "承認待ちです"),
                t("Allow ClosePaw to operate $app?", "ClosePawに$app の操作を許可しますか？") +
                    (state.description.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
            )
        }
        is ShellyRunState.Paused -> {
            val app = state.packageName?.let { quote(labelOf(it)) }
            Content(
                t("ClosePaw paused", "ClosePawを一時停止しました"),
                if (app != null) {
                    t(
                        "You opened $app, which ClosePaw was using. Tap Resume when you're done with it, or Stop.",
                        "ClosePawが使用中の$app が開かれたため一時停止しました。使い終わったら「再開」、やめる場合は「停止」を押してください。",
                    )
                } else {
                    t("Tap Resume to continue, or Stop.", "続けるには「再開」、やめる場合は「停止」を押してください。")
                },
            )
        }
        is ShellyRunState.Finished -> when (state.kind) {
            ShellyRunState.Finished.Kind.DONE ->
                Content(t("ClosePaw finished", "ClosePawの作業が完了しました"), state.message ?: "")
            ShellyRunState.Finished.Kind.STOPPED ->
                Content(t("ClosePaw stopped", "ClosePawを停止しました"), state.message ?: "")
            ShellyRunState.Finished.Kind.FAILED ->
                Content(t("ClosePaw couldn't finish", "ClosePawの作業が失敗しました"), state.message ?: "")
        }
    }

    fun blocked(block: ShellyBackgroundRun.Block): Content = when (block) {
        ShellyBackgroundRun.Block.ACCESSIBILITY_OFF -> Content(
            t("ClosePaw can't run the Shelly task", "Shellyからの依頼を実行できません"),
            t("Enable ClosePaw's accessibility service, then try again.", "ClosePawのユーザー補助サービスを有効にしてから再度お試しください。"),
        )
        ShellyBackgroundRun.Block.ONBOARDING_REQUIRED -> Content(
            t("ClosePaw can't run the Shelly task", "Shellyからの依頼を実行できません"),
            t("Finish ClosePaw setup first. Tap to open ClosePaw.", "先にClosePawの初期設定を完了してください。タップで開きます。"),
        )
        ShellyBackgroundRun.Block.OVERLAY_PERMISSION_MISSING -> Content(
            t("ClosePaw can't run the Shelly task", "Shellyからの依頼を実行できません"),
            t("ClosePaw needs the \"display over other apps\" permission. Tap to open ClosePaw.", "ClosePawに「他のアプリの上に表示」の権限が必要です。タップで開きます。"),
        )
        ShellyBackgroundRun.Block.CREDENTIALS_MISSING -> Content(
            t("ClosePaw can't run the Shelly task", "Shellyからの依頼を実行できません"),
            t("Sign in or add an API key for the selected model. Tap to open ClosePaw.", "選択中のモデルのサインインまたはAPIキー設定が必要です。タップで開きます。"),
        )
        ShellyBackgroundRun.Block.BUSY -> Content(
            t("ClosePaw is busy", "ClosePawは別の作業中です"),
            t("Another task is running. Stop it or wait, then ask Shelly again.", "別のタスクを実行中です。停止するか完了を待ってから、もう一度Shellyから依頼してください。"),
        )
        ShellyBackgroundRun.Block.VIRTUAL_DISPLAY_UNAVAILABLE -> Content(
            t("Shizuku isn't running — task not started", "Shizukuが動いていないため開始しませんでした"),
            t(
                "Background mode needs Shizuku. Start Shizuku and ask again, or tap to run it in the foreground (ClosePaw will take over the screen).",
                "バックグラウンド実行にはShizukuが必要です。Shizukuを起動して再度依頼するか、タップしてフォアグラウンドで実行してください（画面を操作します）。",
            ),
        )
    }

    val actionStop get() = t("Stop", "停止")
    val actionResume get() = t("Resume", "再開")
    val actionAllowAlways get() = t("Always allow", "常に許可")
    val actionAllowSession get() = t("Allow this time", "今回のみ許可")
    val actionReject get() = t("Reject", "拒否")
    val lockScreenSummary get() = t("Shelly task — unlock for details", "Shellyタスク — 詳細はロック解除後に表示")
    val mainDisplaySettingTitle get() = t("Allow Shelly to control the main screen", "Shellyにメイン画面の操作を許可")
    val mainDisplaySettingOn get() = t(
        "When the virtual display (Shizuku) is unavailable, Shelly may run tasks on your main screen. ClosePaw will take over the screen.",
        "仮想ディスプレイ（Shizuku）が使えないとき、Shellyからの依頼をメイン画面で実行します。ClosePawが画面を操作します。",
    )
    val mainDisplaySettingOff get() = t(
        "Shelly tasks only run in the background on the virtual display; without Shizuku they are refused.",
        "Shellyからの依頼は仮想ディスプレイ上でのみ実行します。Shizukuが使えないときは実行しません。",
    )
    val channelStatus get() = t("Shelly tasks (status)", "Shellyタスク（状態）")
    val channelAttention get() = t("Shelly tasks (needs attention)", "Shellyタスク（要対応）")
}
