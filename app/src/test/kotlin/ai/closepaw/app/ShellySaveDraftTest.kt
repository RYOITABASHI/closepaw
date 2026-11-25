package ai.closepaw.app

import ai.closepaw.draft.DraftPlatform
import ai.closepaw.draft.DraftSite
import ai.closepaw.draft.SaveDraftRequestParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellySaveDraftTest {

    @Test
    fun unknownHelperTaskBlocksAdmissionWithKnownNonChromeWindow() {
        val other = "com.example.other"
        assertThat(ShellySaveDraft.chromeInFront(other, null, requireTaskObservation = true)).isTrue()
        assertThat(ShellySaveDraft.chromeInFront(other, false, requireTaskObservation = true)).isFalse()
        assertThat(ShellySaveDraft.chromeInFront(other, null, requireTaskObservation = false)).isFalse()
    }

    private val reqId = "1760500000000-0123abcd"
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun target(
        action: String? = ShellySaveDraft.ACTION_SAVE_DRAFT,
        verified: Boolean = true,
        platform: String? = "note",
        subdomain: String? = null,
        req: String? = reqId,
        non: String? = nonce,
        sha: String? = "a".repeat(64),
    ) = ShellySaveDraft.parseTarget(action, verified, platform, subdomain, req, non, sha)

    @Test
    fun `a verified request becomes a target`() {
        assertThat(target()!!.site.platform).isEqualTo(DraftPlatform.NOTE)
        val substack = target(platform = "substack", subdomain = "example-pub")!!
        assertThat(substack.site.newDraftUrl).startsWith("https://example-pub.substack.com/")
        assertThat(substack.correlation.reqId).isEqualTo(reqId)
    }

    @Test
    fun `everything else is ignored`() {
        assertThat(target(verified = false)).isNull()
        assertThat(target(action = "android.intent.action.VIEW")).isNull()
        assertThat(target(platform = "other")).isNull()
        assertThat(target(platform = null)).isNull()
        assertThat(target(platform = "substack")).isNull()
        assertThat(target(platform = "substack", subdomain = "evil.com/")).isNull()
        assertThat(target(subdomain = "unexpected")).isNull()
    }

    @Test
    fun `the file hash is required and must be lowercase SHA-256 hex`() {
        assertThat(target()!!.requestSha256).isEqualTo("a".repeat(64))
        assertThat(target(sha = null)).isNull()
        assertThat(target(sha = "")).isNull()
        assertThat(target(sha = "a".repeat(63))).isNull()
        assertThat(target(sha = "a".repeat(65))).isNull()
        assertThat(target(sha = "A".repeat(64))).isNull()
        assertThat(target(sha = "g".repeat(64))).isNull()
    }

    @Test
    fun `a request nobody can answer is refused`() {
        assertThat(target(req = null)).isNull()
        assertThat(target(non = null)).isNull()
        assertThat(target(non = "short")).isNull()
    }

    private fun refusal(
        accessibility: Boolean = true,
        onboarded: Boolean = true,
        busy: Boolean = false,
        vd: Boolean = true,
        locked: Boolean = false,
        chrome: Boolean = false,
    ) = ShellySaveDraft.refusal(accessibility, onboarded, busy, vd, locked, chrome)

    @Test
    fun `refusals use their own codes and a clear run starts`() {
        assertThat(refusal()).isNull()
        assertThat(refusal(accessibility = false)).isEqualTo(11)
        assertThat(refusal(onboarded = false)).isEqualTo(12)
        assertThat(refusal(busy = true)).isEqualTo(15)
        assertThat(refusal(vd = false)).isEqualTo(16)
        assertThat(refusal(locked = true)).isEqualTo(ShellySaveDraft.RESULT_DEVICE_LOCKED)
        assertThat(refusal(chrome = true)).isEqualTo(ShellySaveDraft.RESULT_CHROME_IN_USE)
    }

    @Test
    fun `missing credentials do not refuse a run that uses no model`() {
        val parameters = ShellySaveDraft::refusal.parameters.map { it.name }
        assertThat(parameters).doesNotContain("credentialsMissing")
    }

    @Test
    fun `an unknown foreground app counts as Chrome`() {
        assertThat(ShellySaveDraft.chromeInFront(null)).isTrue()
        assertThat(ShellySaveDraft.chromeInFront("com.android.chrome")).isTrue()
        assertThat(ShellySaveDraft.chromeInFront("com.example.other")).isFalse()
    }

    @Test
    fun `no focused window is not Chrome when the task list says so`() {
        assertThat(ShellySaveDraft.chromeInFront(null, frontTaskIsChrome = false)).isFalse()
        assertThat(ShellySaveDraft.chromeInFront(null, frontTaskIsChrome = true)).isTrue()
        assertThat(ShellySaveDraft.chromeInFront(null, frontTaskIsChrome = null)).isTrue()
        assertThat(ShellySaveDraft.chromeInFront("com.example.other", frontTaskIsChrome = true)).isTrue()
        // The run's own Chrome on the virtual display can be the active window; the task list wins.
        assertThat(ShellySaveDraft.chromeInFront("com.android.chrome", frontTaskIsChrome = false)).isFalse()
        assertThat(ShellySaveDraft.chromeInFront("com.android.chrome", frontTaskIsChrome = null)).isTrue()
    }

    @Test
    fun `only Chrome's activity window on the main display stops a run`() {
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.android.chrome", true, true)).isTrue()
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.android.chrome", true, false)).isFalse()
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.android.chrome", false, true)).isFalse()
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.example.other", true, true)).isFalse()
        assertThat(ShellySaveDraft.ownerOpenedChrome(null, true, true)).isFalse()
    }

    private val site = DraftSite.of(DraftPlatform.NOTE, null)!!

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun badReason(read: () -> ByteArray): String =
        (ShellySaveDraft.readRequest(site, "a".repeat(64), read) as SaveDraftRequestParser.Parsed.Bad).reason

    private fun fileBytes(html: String, extra: String = ""): ByteArray =
        "{\"version\":1,\"title\":\"t\",\"html\":\"$html\"$extra}".toByteArray()

    @Test
    fun `errors from hostile input become a refusal, not a dead process`() {
        assertThat(badReason { throw StackOverflowError() }).isEqualTo("file-unreadable")
        assertThat(badReason { throw OutOfMemoryError() }).isEqualTo("file-unreadable")
        assertThat(badReason { throw java.io.IOException("x") }).isEqualTo("file-unreadable")
        assertThat(badReason { throw SecurityException("x") }).isEqualTo("file-no-grant")
    }

    @Test
    fun `a file of thousands of attributes is refused whole`() {
        val html = "<p" + " a=\\\"b\\\"".repeat(50_000) + ">t</p>"
        val bytes = fileBytes(html)
        val parsed = ShellySaveDraft.readRequest(site, sha256(bytes)) { bytes }
        assertThat((parsed as SaveDraftRequestParser.Parsed.Bad).reason).isEqualTo("html-unreadable-tag")
    }

    // ── File hash ──

    private val substackSite = DraftSite.of(DraftPlatform.SUBSTACK, "example-pub")!!
    private val imageHtml = "<img src=\\\"https://img.example.com/a.jpg\\\">some text here"
    private val hostsField = ",\"image_hosts\":[\"img.example.com\"]"

    @Test
    fun `the bytes that match the hash are what get parsed`() {
        val bytes = fileBytes(imageHtml, hostsField)
        val parsed = ShellySaveDraft.readRequest(substackSite, sha256(bytes)) { bytes }
        assertThat(parsed).isInstanceOf(SaveDraftRequestParser.Parsed.Ok::class.java)
    }

    @Test
    fun `any change to the file after approval is refused before parsing`() {
        val approved = fileBytes("<p>some approved text</p>")
        val swapped = fileBytes(imageHtml, hostsField)
        val parsed = ShellySaveDraft.readRequest(substackSite, sha256(approved)) { swapped }
        assertThat((parsed as SaveDraftRequestParser.Parsed.Bad).reason).isEqualTo("request-hash-mismatch")

        val oneByte = approved.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        val again = ShellySaveDraft.readRequest(site, sha256(approved)) { oneByte }
        assertThat((again as SaveDraftRequestParser.Parsed.Bad).reason).isEqualTo("request-hash-mismatch")
    }

    @Test
    fun `garbage with a wrong hash reports the hash, proving nothing was parsed first`() {
        assertThat(badReason { "not json".toByteArray() }).isEqualTo("request-hash-mismatch")
    }

    @Test
    fun `the file is read exactly once`() {
        val bytes = fileBytes("<p>some body text</p>")
        var reads = 0
        ShellySaveDraft.readRequest(site, sha256(bytes)) {
            reads++
            bytes
        }
        assertThat(reads).isEqualTo(1)
    }

    @Test
    fun `invalid UTF-8 is refused, not replaced`() {
        val bytes = byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)
        val parsed = ShellySaveDraft.readRequest(site, sha256(bytes)) { bytes }
        assertThat((parsed as SaveDraftRequestParser.Parsed.Bad).reason).isEqualTo("file-not-utf8")
    }

    @Test
    fun `image hosts are never taken from the intent`() {
        val source = java.io.File("src/main/kotlin/ai/closepaw/app/ShellySaveDraftReceiver.kt").readText()
        assertThat(source).doesNotContain("image_hosts")
        assertThat(source.lowercase()).doesNotContain("imagehosts")
        assertThat(source).doesNotContain("SaveDraftRequestParser.parse(")
        assertThat(source).contains("ShellySaveDraft.readRequest(target.site, target.requestSha256)")
    }

    // ── Reading under the cap ──

    @Test
    fun `a read stops at the size limit and at the deadline`() {
        val input = { java.io.ByteArrayInputStream(ByteArray(300_000)) }
        assertThat(ShellySaveDraft.readLimited(input(), 300_000) { false }).hasLength(300_000)
        assertThat(runCatching { ShellySaveDraft.readLimited(input(), 299_999) { false } }.exceptionOrNull())
            .isInstanceOf(java.io.IOException::class.java)
        assertThat(runCatching { ShellySaveDraft.readLimited(input(), 300_000) { true } }.exceptionOrNull())
            .isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `the deadline ends a slow read part way`() {
        var checks = 0
        val failure = runCatching {
            ShellySaveDraft.readLimited(java.io.ByteArrayInputStream(ByteArray(1_000_000)), 2_000_000) { ++checks > 3 }
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(java.io.IOException::class.java)
        assertThat(checks).isEqualTo(4)
    }

    private class Clock(var ms: Long = 1_000L)

    private fun window(clock: Clock) = ShellySaveDraft.AnswerWindow(ShellySaveDraft.ANSWER_CAP_MS) { clock.ms }

    @Test
    fun `the cap sits well under the caller's wait and the system limit`() {
        assertThat(ShellySaveDraft.ANSWER_CAP_MS).isAtMost(3_000L)
    }

    @Test
    fun `a decision in time may start`() {
        val clock = Clock()
        val window = window(clock)
        clock.ms += ShellySaveDraft.ANSWER_CAP_MS - 1
        assertThat(window.isExpired()).isFalse()
        assertThat(window.claim()).isEqualTo(ShellySaveDraft.AnswerWindow.Claim.IN_TIME)
    }

    @Test
    fun `a decision after the cap is refused, even if the watchdog has not run`() {
        val clock = Clock()
        val window = window(clock)
        clock.ms += ShellySaveDraft.ANSWER_CAP_MS
        assertThat(window.isExpired()).isTrue()
        assertThat(window.claim()).isEqualTo(ShellySaveDraft.AnswerWindow.Claim.LATE)
    }

    @Test
    fun `only the first claim answers, so a late worker cannot start after the watchdog`() {
        val clock = Clock()
        val window = window(clock)
        clock.ms += ShellySaveDraft.ANSWER_CAP_MS
        assertThat(window.claim()).isEqualTo(ShellySaveDraft.AnswerWindow.Claim.LATE)
        assertThat(window.claim()).isEqualTo(ShellySaveDraft.AnswerWindow.Claim.ALREADY_ANSWERED)
    }

    @Test
    fun `the watchdog cannot answer after the worker has`() {
        val clock = Clock()
        val window = window(clock)
        assertThat(window.claim()).isEqualTo(ShellySaveDraft.AnswerWindow.Claim.IN_TIME)
        clock.ms += ShellySaveDraft.ANSWER_CAP_MS
        assertThat(window.claim()).isEqualTo(ShellySaveDraft.AnswerWindow.Claim.ALREADY_ANSWERED)
    }

    @Test
    fun `the receiver starts a run only from an in-time claim`() {
        val source = java.io.File("src/main/kotlin/ai/closepaw/app/ShellySaveDraftReceiver.kt").readText()
            .replace("\r\n", "\n")
        assertThat(source).contains("MAIN.postDelayed(watchdog, ShellySaveDraft.ANSWER_CAP_MS)")
        assertThat(Regex("decideAndStart\\(target").findAll(source).count()).isEqualTo(1)
        val inTime = source.substringAfter("val checks = try {")
            .substringAfter("Claim.IN_TIME -> {").substringBefore("}\n")
        assertThat(inTime).contains("respond { decideAndStart(target, checks!!)")
        // A deadline that passes between the claim and the commit refuses as too slow.
        assertThat(inTime).contains("?: tooSlow()")
        val decide = source.substringAfter("private fun decideAndStart(")
        assertThat(decide.substringBefore("REPLAY_GUARD.firstUse")).contains("checks.window.isExpired()")
        // The foreground observation runs in the worker before the main-thread decision.
        assertThat(decide).doesNotContain("isChromeInFront()")
        assertThat(source.substringAfter("private fun gather(").substringBefore("private fun decideAndStart"))
            .contains("isChromeInFront()")
    }

    @Test
    fun `the broadcast finishes once, whatever the decision does`() {
        fun outcome(decide: () -> Pair<Int, String>, reply: (Int, String) -> Unit): Pair<List<String>, Int> {
            val replies = mutableListOf<String>()
            var finished = 0
            ShellySaveDraft.answer(
                decide = decide,
                reply = { code, data ->
                    replies += "$code $data"
                    reply(code, data)
                },
                finish = { finished++ },
            )
            return replies to finished
        }

        assertThat(outcome({ 1 to "STARTED" }, { _, _ -> })).isEqualTo(listOf("1 STARTED") to 1)
        assertThat(outcome({ throw IllegalStateException() }, { _, _ -> }))
            .isEqualTo(listOf("10 START_FAILED") to 1)
        assertThat(outcome({ throw StackOverflowError() }, { _, _ -> }))
            .isEqualTo(listOf("10 START_FAILED") to 1)
        assertThat(outcome({ 1 to "STARTED" }, { _, _ -> throw StackOverflowError() }).second).isEqualTo(1)
    }

    @Test
    fun `verification runs inside the answer window, on the worker`() {
        // Gate fix round 2, finding 4.
        val source = java.io.File("src/main/kotlin/ai/closepaw/app/ShellySaveDraftReceiver.kt").readText()
        val onReceive = source.substringAfter("override fun onReceive(").substringBefore("private fun admit(")
        assertThat(onReceive.indexOf("ShellySaveDraft.AnswerWindow(")).isLessThan(onReceive.indexOf("goAsync()"))
        assertThat(onReceive).doesNotContain("ShellyTrustedCaller.verify(")
        assertThat(onReceive).doesNotContain("isShellyFile(")
        val worker = onReceive.substringAfter("WORKER.execute {")
        assertThat(worker.indexOf("admit(app, intent, sentFrom)")).isLessThan(worker.indexOf("gather(app, target, uri, window)"))
        assertThat(source.substringAfter("private fun admit(")).contains("ShellyTrustedCaller.verify(intent, app.packageManager)")
    }

    @Test
    fun `the draft start waits for prior teardown and reports independently of its notification`() {
        // Gate fix round 2, findings 2-4.
        val source = java.io.File("src/main/kotlin/ai/closepaw/app/AgentService.kt").readText()
        val start = source.substringAfter("internal fun startShellySaveDraft(").substringBefore("private fun stopDraftForForegroundSession")
        val beforeLaunch = start.substringBefore("serviceScope.launch(")
        assertThat(beforeLaunch).doesNotContain("shellyNotifier.render(")
        val body = start.substringAfter("serviceScope.launch(")
        assertThat(body.indexOf("awaitClear(REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS, exceptOwner = browser)"))
            .isLessThan(body.indexOf("} else if (browser.start()) {"))
        val report = body.substringAfter("if (!reported.get()) {")
        assertThat(report.indexOf("renderDraftFinished(finalResult)")).isLessThan(report.indexOf("ShellyResultCallback.send("))
        assertThat(report.substringBefore("ShellyResultCallback.send(")).contains("catch (e: Throwable)")
        assertThat(report.indexOf("ShellyResultCallback.send(")).isLessThan(report.indexOf("reported.set(true)"))
    }

    @Test
    fun `the draft fails closed on an unfinished or deferred display-0 restore`() {
        // Gate fix round 3, finding 2.
        val source = java.io.File("src/main/kotlin/ai/closepaw/app/AgentService.kt").readText()
        val body = source.substringAfter("internal fun startShellySaveDraft(").substringBefore("private fun stopDraftForForegroundSession")
            .substringAfter("serviceScope.launch(")
        assertThat(body).contains("withTimeoutOrNull(REPLACED_SESSION_SHUTDOWN_TIMEOUT_MS) { restore.join() } != null")
        assertThat(body.indexOf("restore.join()")).isLessThan(body.indexOf("displayZeroRestorePending()"))
        // Anchored on the executable call, not the comment that names it (gate fix round 4, finding 2).
        val startCall = "} else if (browser.start()) {"
        assertThat(Regex(Regex.escape(startCall)).findAll(body).count()).isEqualTo(1)
        assertThat(body.indexOf("attachedPlatform?.displayZeroRestorePending() == true")).isAtLeast(0)
        assertThat(body.indexOf("attachedPlatform?.displayZeroRestorePending() == true")).isLessThan(body.indexOf(startCall))
        assertThat(body.indexOf("val clear = barrierClear && !deferredPending")).isLessThan(body.indexOf(startCall))
        assertThat(body).contains("val clear = barrierClear && !deferredPending")
        val platform = java.io.File("src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayPlatform.kt").readText()
        // Each restore trigger uses the counted launcher. The lifecycle also has its own jobs.
        for (trigger in listOf("onViewerClosedRecheckDeferral", "onScreenOff", "restoreNowWithoutDeferral")) {
            val triggerBody = platform.substringAfter("fun $trigger(").substringBefore("\n    }")
            assertThat(triggerBody).contains("launchDeferralWork")
            assertThat(triggerBody).doesNotContain("deferralScope.launch")
        }
        val countedLaunch = platform.substringAfter("fun launchDeferralWork(").substringBefore("\n    }")
        assertThat(countedLaunch.substringBefore("deferralScope.launch")).contains("deferralWorkRunning.incrementAndGet()")
        assertThat(countedLaunch).contains("invokeOnCompletion { deferralWorkRunning.decrementAndGet() }")
    }

    @Test
    fun `a late admission rejection answers request-too-slow`() {
        // Gate fix round 3, non-blocking: LATE takes Shelly's retry path, not its refusal path.
        val source = java.io.File("src/main/kotlin/ai/closepaw/app/ShellySaveDraftReceiver.kt").readText()
        val rejection = source.substringAfter("if (admitted == null) {").substringBefore("return@execute")
        assertThat(rejection).contains("ShellySaveDraft.AnswerWindow.Claim.LATE -> respond(tooSlow)")
        assertThat(rejection.indexOf("Claim.IN_TIME")).isLessThan(rejection.indexOf("RESULT_DATA_REJECTED"))
    }

    @Test
    fun `the receiver routes every path through the guarded answer`() {
        val source = java.io.File("src/main/kotlin/ai/closepaw/app/ShellySaveDraftReceiver.kt").readText()
        assertThat(source).contains("ShellySaveDraft.answer(")
        assertThat(source).contains("finish = { pending.finish() }")
        assertThat(Regex("pending\\.finish\\(\\)").findAll(source).count()).isEqualTo(1)
        assertThat(source).contains("catch (e: Throwable)")
    }

    @Test
    fun `the draft command never reaches logcat`() {
        val controller = java.io.File("src/main/kotlin/ai/closepaw/platform/virtualdisplay/VirtualDisplayAppController.kt").readText()
        val start = controller.substringAfter("private suspend fun startChrome").substringBefore("suspend fun launchApp")
        assertThat(start).contains("shizuku.executeQuietShellCommand(command)")
        assertThat(start).doesNotContain("executeShellCommand(")
        val client = java.io.File("src/main/kotlin/ai/closepaw/platform/virtualdisplay/ShizukuClient.kt").readText()
        val quiet = client.substringAfter("fun executeQuietShellCommand(").substringBefore("\n    }")
        assertThat(quiet).contains("shellExecutor.execute(command, quiet = true)")
    }

    @Test
    fun `one request id starts one run`() {
        val guard = ShellySaveDraft.ReplayGuard()
        assertThat(guard.firstUse(reqId)).isTrue()
        assertThat(guard.firstUse(reqId)).isFalse()
        assertThat(guard.firstUse("1760500000001-0123abcd")).isTrue()
    }

    private val snapshot = listOf("device_share", "draft-0123456789abcdef0123456789abcdef", "request.json")

    @Test
    fun `below API 34 only an approved-draft snapshot without its own grant passes`() {
        val authority = "dev.shelly.terminal.shelly.fileprovider"
        fun ok(
            sdk: Int = 33,
            auth: String? = authority,
            path: List<String> = snapshot,
            grant: Boolean = false,
            clip: Boolean = false,
        ) = ShellySaveDraft.legacySenderProofOk(sdk, auth, path, grant, clip)

        assertThat(ok()).isTrue()
        assertThat(ok(sdk = 31)).isTrue()
        // What shipped Shelly's share command can grant to third parties: <reqId> directories.
        assertThat(ok(path = listOf("device_share", "1760500000000-0123abcd", "request.json"))).isFalse()
        assertThat(ok(path = listOf("device_share", "draft-0123", "request.json"))).isFalse()
        assertThat(ok(path = listOf("device_share", "draft-0123456789ABCDEF0123456789ABCDEF", "request.json"))).isFalse()
        assertThat(ok(path = snapshot + "x")).isFalse()
        assertThat(ok(path = listOf(snapshot[0], snapshot[1], "other.json"))).isFalse()
        assertThat(ok(auth = "evil.provider")).isFalse()
        assertThat(ok(auth = null)).isFalse()
        // A broadcaster passing on a grant it holds is not Shelly's own per-send grant.
        assertThat(ok(grant = true)).isFalse()
        assertThat(ok(clip = true)).isFalse()
    }

    @Test
    fun `from API 34 the named sender decides and the URI shape is not required`() {
        assertThat(ShellySaveDraft.legacySenderProofOk(34, null, emptyList(), carriesUriGrant = true, hasClipData = true)).isTrue()
        assertThat(ShellySaveDraft.legacySenderProofOk(37, "x", snapshot, carriesUriGrant = false, hasClipData = false)).isTrue()
    }
}
