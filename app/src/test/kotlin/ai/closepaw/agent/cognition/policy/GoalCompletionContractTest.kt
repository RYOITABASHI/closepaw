package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoalCompletionContractTest {

    private val goal = "In the Spotify app, open Your Library, tap +, choose Playlist, and name it " +
        "\"Smells Like 1991\". Then use 'Add to this playlist' to add 10 songs from 1991. " +
        "Keep going until all 10 are added, then check the count."

    @Test
    fun `wrap prepends the contract once and keeps the task verbatim`() {
        val wrapped = GoalCompletionContract.wrap(goal)
        assertThat(wrapped).startsWith("COMPLETION CONTRACT")
        assertThat(wrapped).contains("Never declare success after only part of the items")
        assertThat(wrapped).contains("Do not start media playback")
        assertThat(wrapped).endsWith("TASK:\n$goal")
        assertThat(GoalCompletionContract.wrap(wrapped)).isEqualTo(wrapped)
    }

    @Test
    fun `required count comes from the task, not from the contract text`() {
        assertThat(GoalCompletionContract.requiredCount(goal)).isEqualTo(10)
        assertThat(GoalCompletionContract.requiredCount(GoalCompletionContract.wrap(goal))).isEqualTo(10)
        assertThat(GoalCompletionContract.requiredCount("Open Settings and turn on Wi-Fi")).isNull()
        assertThat(GoalCompletionContract.requiredCount("Save the 3 photos and the 5 contacts")).isEqualTo(5)
    }

    @Test
    fun `reported count accepts units and progress phrases`() {
        assertThat(GoalCompletionContract.reportedCount("Added 10 songs to the playlist")).isEqualTo(10)
        assertThat(GoalCompletionContract.reportedCount("Done: 10 of 10 added")).isEqualTo(10)
        assertThat(GoalCompletionContract.reportedCount("7/10 so far")).isEqualTo(7)
        assertThat(GoalCompletionContract.reportedCount("Created the playlist and added Jeremy")).isNull()
    }

    @Test
    fun `success with one song out of ten is refused and the agent is told to continue`() {
        val wrapped = GoalCompletionContract.wrap(goal)
        val oneSong = GoalCompletionContract.completionRejection(
            wrapped, "success", "Created playlist Smells Like 1991 and added 1 song (Jeremy).",
        )
        assertThat(oneSong).isNotNull()
        assertThat(oneSong).contains("1 of 10")
        val noCount = GoalCompletionContract.completionRejection(
            wrapped, "success", "Created the playlist and added Jeremy.",
        )
        assertThat(noCount).isNotNull()
        assertThat(noCount).contains("requires 10 items")
    }

    @Test
    fun `success with the full count, failures and non counted goals pass`() {
        val wrapped = GoalCompletionContract.wrap(goal)
        assertThat(GoalCompletionContract.completionRejection(wrapped, "success", "Added 10 of 10 songs; count verified on screen.")).isNull()
        assertThat(GoalCompletionContract.completionRejection(wrapped, "success", "Playlist now has 12 songs.")).isNull()
        assertThat(GoalCompletionContract.completionRejection(wrapped, "failure", "Spotify shows an error, could not add songs.")).isNull()
        assertThat(GoalCompletionContract.completionRejection("Open Settings and turn on Wi-Fi", "success", "Wi-Fi is on.")).isNull()
        val gate = GoalCompletionContract.gateFor(wrapped)
        assertThat(gate("success", "added 2 songs")).isNotNull()
        assertThat(gate("success", "added 10 songs")).isNull()
    }

    @Test
    fun `device side evidence overrides the agent's claim`() {
        val wrapped = GoalCompletionContract.wrap(goal)
        // Agent claims 10 of 10 but ClosePaw saw a single "Added to" confirmation.
        val claimed = GoalCompletionContract.completionRejection(wrapped, "success", "Added 10 of 10 songs.", observed = 1)
        assertThat(claimed).isNotNull()
        assertThat(claimed).contains("observed 1 of 10")
        // Enough confirmations: the claim is accepted even without a stated count.
        assertThat(GoalCompletionContract.completionRejection(wrapped, "success", "Playlist is ready.", observed = 10)).isNull()
        // Evidence present but the agent still claims fewer than required: still not finished.
        assertThat(GoalCompletionContract.completionRejection(wrapped, "success", "Added 4 songs so far.", observed = 10)).isNotNull()
        // Declared failure is never gated.
        assertThat(GoalCompletionContract.completionRejection(wrapped, "failure", "Could not add songs.", observed = 0)).isNull()
        var observed = 0
        val gate = GoalCompletionContract.gateFor(wrapped) { observed }
        assertThat(gate("success", "10 of 10")).isNotNull()
        observed = 10
        assertThat(gate("success", "10 of 10")).isNull()
    }
}
