package ai.closepaw.app

import ai.closepaw.draft.DraftPlatform
import ai.closepaw.draft.DraftSite
import ai.closepaw.draft.SaveDraftRequestParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellySaveDraftTest {

    private val reqId = "1760500000000-0123abcd"
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun target(
        action: String? = ShellySaveDraft.ACTION_SAVE_DRAFT,
        verified: Boolean = true,
        platform: String? = "note",
        subdomain: String? = null,
        req: String? = reqId,
        non: String? = nonce,
    ) = ShellySaveDraft.parseTarget(action, verified, platform, subdomain, req, non)

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
        assertThat(target(action = ShellyBackgroundRun.ACTION_RUN_GOAL)).isNull()
        assertThat(target(platform = "other")).isNull()
        assertThat(target(platform = null)).isNull()
        assertThat(target(platform = "substack")).isNull()
        assertThat(target(platform = "substack", subdomain = "evil.com/")).isNull()
        assertThat(target(subdomain = "unexpected")).isNull()
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
    fun `only Chrome's activity window on the main display stops a run`() {
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.android.chrome", true, true)).isTrue()
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.android.chrome", true, false)).isFalse()
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.android.chrome", false, true)).isFalse()
        assertThat(ShellySaveDraft.ownerOpenedChrome("com.example.other", true, true)).isFalse()
        assertThat(ShellySaveDraft.ownerOpenedChrome(null, true, true)).isFalse()
    }

    private val site = DraftSite.of(DraftPlatform.NOTE, null)!!

    private fun badReason(read: () -> String): String =
        (ShellySaveDraft.readRequest(site, read) as SaveDraftRequestParser.Parsed.Bad).reason

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
        val text = "{\"version\":1,\"title\":\"t\",\"html\":\"$html\"}"
        assertThat(badReason { text }).isEqualTo("html-unreadable-tag")
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
        assertThat(controller.substringAfter("private suspend fun startChrome")).contains("executeQuietShellCommand")
        val routine = java.io.File("src/main/kotlin/ai/closepaw/draft/SaveDraftRoutine.kt").readText()
        assertThat(routine).doesNotContain("Log.")
    }

    @Test
    fun `one request id starts one run`() {
        val guard = ShellySaveDraft.ReplayGuard()
        assertThat(guard.firstUse(reqId)).isTrue()
        assertThat(guard.firstUse(reqId)).isFalse()
        assertThat(guard.firstUse("1760500000001-0123abcd")).isTrue()
    }
}
