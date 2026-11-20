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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedFlagMasks() {
        VdProtocol.flagNames(0xE48)
    }
}
