package ai.closepaw.platform.virtualdisplay

/**
 * Safety net for unattended runs: if an agent action on the virtual display starts music (e.g.
 * tapping a song row in Spotify), pause it right away. Pure decision; the Android side compares
 * `AudioManager.isMusicActive` before/after the action and runs
 * `cmd media_session dispatch pause` through Shizuku when this says so.
 */
object PlaybackGuard {
    /**
     * @param unattended Shelly-handed run (nobody is watching, playback would leak into the room
     *   and the demo recording).
     * @param musicActiveBefore music was already playing before the action (the user's own
     *   playback: never touch it).
     * @param musicActiveAfter music is playing after the action.
     */
    fun shouldPause(unattended: Boolean, musicActiveBefore: Boolean, musicActiveAfter: Boolean): Boolean =
        unattended && !musicActiveBefore && musicActiveAfter

    val PAUSE_COMMAND: Array<String> = arrayOf("cmd", "media_session", "dispatch", "pause")
}
