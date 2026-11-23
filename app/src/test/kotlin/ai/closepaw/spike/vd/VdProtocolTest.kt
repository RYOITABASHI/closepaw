package ai.closepaw.spike.vd

import org.junit.Assert.*
import org.junit.Test

class VdProtocolTest {
    @Test fun challengeIsRandomAndShellSafe() {
        val challenges = List(64) { VdProtocol.newChallenge() }
        assertEquals(64, challenges.toSet().size)
        assertTrue(challenges.all { it.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun registrationEncodingRequiresExactVersionAndCanonicalChallenge() {
        val challenge = VdProtocol.newChallenge()
        assertEquals(challenge, VdProtocol.decodeRegistration(VdProtocol.encodeRegistration(challenge)))
        assertNull(VdProtocol.decodeRegistration("v2:$challenge"))
        assertNull(VdProtocol.decodeRegistration("v1:${challenge}0"))
        assertNull(VdProtocol.decodeRegistration("v1:${"A".repeat(64)}"))
        assertNull(VdProtocol.decodeRegistration(null))
    }

    @Test fun rejectedRegistrationsDoNotConsumeChallengeAndAcceptedRegistrationCannotReplay() {
        val challenge = VdProtocol.newChallenge()
        val permit = VdProtocol.Registration(challenge)
        val encoded = VdProtocol.encodeRegistration(challenge)
        val secret = VdProtocol.newChallenge()
        assertFalse(permit.claim(2000, 0, 41, 41, encoded, secret))
        assertFalse(permit.claim(0, 41, 41, 41, encoded, secret))
        assertFalse(permit.claim(2000, 41, 42, 42, encoded, secret))
        assertFalse(permit.claim(2000, 41, 42, 41, encoded, secret))
        assertFalse(permit.claim(2000, 41, 0, 41, encoded, secret))
        assertFalse(permit.claim(2000, 41, 41, 41, encoded, challenge))
        assertFalse(permit.claim(2000, 41, 41, 41, encoded, null))
        assertFalse(permit.claim(2000, 41, 41, 41,
            VdProtocol.encodeRegistration(VdProtocol.newChallenge()), secret))
        assertTrue(permit.claim(2000, 41, 41, 41, encoded, secret))
        assertFalse(permit.claim(2000, 41, 41, 41, encoded, secret))
    }

    @Test fun oneWayCallsRequireTheSessionSecretEvenWithoutACallerPid() {
        val secret = VdProtocol.newChallenge()
        val peer = VdProtocol.Peer(2000, 41, secret)
        assertTrue(peer.accepts(2000, 0, secret))
        assertTrue(peer.accepts(2000, 41, secret))
        assertFalse(peer.accepts(2000, 42, secret))
        assertFalse(peer.accepts(0, 0, secret))
        assertFalse(peer.accepts(2000, 0, null))
        assertFalse(peer.accepts(2000, 41, VdProtocol.newChallenge()))
    }

    @Test fun cleanupNeedsProofOfExitOnceTheHelperHasBeenEnabled() {
        val launch = VdProtocol.LaunchGuard()
        assertTrue(launch.deathConfirmed(false))
        assertThrows(IllegalStateException::class.java) { launch.enable() }
        launch.recordPid(41)
        assertEquals(0, launch.pidToVerify())
        assertTrue(launch.deathConfirmed(false))
        launch.enable()
        assertEquals(41, launch.pidToVerify())
        assertFalse(launch.deathConfirmed(false))
        assertTrue(launch.deathConfirmed(true))
    }

    @Test fun buildsProductionMasksAndReportsOnlyTheAcceptedAttempt() {
        assertEquals(0xE49, VdProtocol.preferredFlags(33))
        assertEquals(0x14E49, VdProtocol.preferredFlags(34))
        assertEquals(
            listOf("PUBLIC", "SECURE", "SUPPORTS_TOUCH", "SHOULD_SHOW_SYSTEM_DECORATIONS",
                "TRUSTED", "OWN_DISPLAY_GROUP"),
            VdProtocol.flagNames(0xE49),
        )
        assertEquals(listOf("OWN_FOCUS", "STEAL_TOP_FOCUS_DISABLED"),
            VdProtocol.flagNames(0x14E49).takeLast(2))
    }

    @Test fun rejectsMalformedRootTaskFields() {
        val valid = listOf("42", "com.example", "-", "android,com.example", "true", "false")
        val invalidFields = mapOf(
            0 to listOf("", "1.0", "2147483648"),
            1 to listOf("", "com.example,android"),
            3 to listOf(",android", "android,", "android,,com.example", " android", "-"),
            4 to listOf("", "TRUE", "null"),
            5 to listOf("-", "FALSE", "0"),
        )
        for ((column, values) in invalidFields) {
            for (value in values) {
                val row = valid.toMutableList().apply { this[column] = value }.joinToString("\t")
                assertThrows(row, IllegalArgumentException::class.java) { VdProtocol.parseRootTaskRows(row) }
            }
        }
    }

    @Test fun acceptsEmptyPackagesAndExplicitNullVisibility() {
        val row = "42\t-\t-\t\t-\tfalse"
        assertArrayEquals(row.split("\t").toTypedArray(), VdProtocol.parseRootTaskRows(row).single())
        assertTrue(VdProtocol.parseRootTaskRows("").isEmpty())
    }

    @Test fun rejectsInvalidRootTaskEncoderPackages() {
        for (invalid in listOf("", "-", "com.bad!", "com.example,android", " android", "a\tb", "a\nb", "a\rb")) {
            assertThrows("topPackage: $invalid", IllegalArgumentException::class.java) {
                VdProtocol.encodeRootTaskRow(42, invalid, null, emptyList(), null, false)
            }
            assertThrows("basePackage: $invalid", IllegalArgumentException::class.java) {
                VdProtocol.encodeRootTaskRow(42, null, invalid, emptyList(), null, false)
            }
            assertThrows("packages: $invalid", IllegalArgumentException::class.java) {
                VdProtocol.encodeRootTaskRow(42, null, null, listOf("android", invalid), null, false)
            }
        }
    }

    @Test fun preservesRootTaskEncoderBytes() {
        val packages = listOf("com.example", "android")
        val row = VdProtocol.encodeRootTaskRow(42, "com.example", null, packages, true, false)
        assertEquals("42\tcom.example\t-\tandroid,com.example\ttrue\tfalse", row)
        val nullVisibilityRow = VdProtocol.encodeRootTaskRow(43, null, null, emptyList(), null, false)
        assertEquals("43\t-\t-\t\t-\tfalse", nullVisibilityRow)

        // Every encoder output must round-trip through parseRootTaskRows.
        val rows = listOf(row, nullVisibilityRow)
        val parsed = VdProtocol.parseRootTaskRows(VdProtocol.encodeRootTaskRows(rows))
        assertEquals(rows.size, parsed.size)
        rows.zip(parsed).forEach { (encoded, fields) ->
            assertArrayEquals(encoded.split("\t").toTypedArray(), fields)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedFlagMasks() {
        VdProtocol.flagNames(0xE48)
    }
}
