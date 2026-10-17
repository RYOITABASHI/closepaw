package ai.closepaw.platform.virtualdisplay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackGuardTest {
    @Test
    fun `pauses only music that an unattended action started`() {
        assertThat(PlaybackGuard.shouldPause(unattended = true, musicActiveBefore = false, musicActiveAfter = true)).isTrue()
        // The user was already listening: never touch it.
        assertThat(PlaybackGuard.shouldPause(unattended = true, musicActiveBefore = true, musicActiveAfter = true)).isFalse()
        // Nothing started.
        assertThat(PlaybackGuard.shouldPause(unattended = true, musicActiveBefore = false, musicActiveAfter = false)).isFalse()
        // Interactive sessions keep today's behaviour.
        assertThat(PlaybackGuard.shouldPause(unattended = false, musicActiveBefore = false, musicActiveAfter = true)).isFalse()
        assertThat(PlaybackGuard.PAUSE_COMMAND.toList()).containsExactly("cmd", "media_session", "dispatch", "pause").inOrder()
    }
}
