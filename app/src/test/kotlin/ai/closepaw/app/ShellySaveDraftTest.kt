package ai.closepaw.app

import ai.closepaw.draft.DraftPlatform
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
    fun `one request id starts one run`() {
        val guard = ShellySaveDraft.ReplayGuard()
        assertThat(guard.firstUse(reqId)).isTrue()
        assertThat(guard.firstUse(reqId)).isFalse()
        assertThat(guard.firstUse("1760500000001-0123abcd")).isTrue()
    }
}
