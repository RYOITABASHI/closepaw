package ai.closepaw.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellyTrustedCallerTest {

    private val pinned = ShellyTrustedCaller.PINNED_SHELLY_CERT_SHA256
    private val pinnedBytes = ShellyTrustedCaller.hexToBytes(pinned.first())!!

    @Test
    fun `trusted when creator is Shelly and cert matches pin`() {
        val trusted = ShellyTrustedCaller.isTrusted("dev.shelly.terminal", pinned) { pkg, cert ->
            pkg == "dev.shelly.terminal" && cert.contentEquals(pinnedBytes)
        }
        assertThat(trusted).isTrue()
    }

    @Test
    fun `untrusted when creator package differs even if cert check would pass`() {
        val trusted = ShellyTrustedCaller.isTrusted("com.evil.app", pinned) { _, _ -> true }
        assertThat(trusted).isFalse()
    }

    @Test
    fun `untrusted when creator package is null`() {
        assertThat(ShellyTrustedCaller.isTrusted(null, pinned) { _, _ -> true }).isFalse()
    }

    @Test
    fun `untrusted when Shelly package is signed with a different cert`() {
        val trusted = ShellyTrustedCaller.isTrusted("dev.shelly.terminal", pinned) { _, _ -> false }
        assertThat(trusted).isFalse()
    }

    @Test
    fun `untrusted when cert check throws`() {
        val trusted = ShellyTrustedCaller.isTrusted("dev.shelly.terminal", pinned) { _, _ ->
            throw SecurityException("package not visible")
        }
        assertThat(trusted).isFalse()
    }

    @Test
    fun `untrusted when pin list is empty or malformed`() {
        assertThat(ShellyTrustedCaller.isTrusted("dev.shelly.terminal", emptyList()) { _, _ -> true })
            .isFalse()
        assertThat(ShellyTrustedCaller.isTrusted("dev.shelly.terminal", listOf("zz")) { _, _ -> true })
            .isFalse()
    }

    @Test
    fun `hexToBytes accepts colon separated fingerprints`() {
        val colon = pinned.first().chunked(2).joinToString(":")
        assertThat(ShellyTrustedCaller.hexToBytes(colon)).isEqualTo(pinnedBytes)
        assertThat(pinnedBytes.size).isEqualTo(32)
    }
}
