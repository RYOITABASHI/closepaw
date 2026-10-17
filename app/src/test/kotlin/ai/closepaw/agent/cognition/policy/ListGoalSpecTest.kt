package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListGoalSpecTest {

    private val goal = "In the Spotify app, open Your Library, tap the + (Create) button, choose Playlist, and name it " +
        "\"Smells Like 1991\". Then use 'Add to this playlist' to add 3 songs from Nirvana, Pearl Jam and Soundgarden. " +
        "Keep going until all 3 are added, then check the count."

    @Test
    fun `parses count, quoted list name and artists`() {
        val spec = ListGoalSpec.parse(GoalCompletionContract.wrap(goal))
        assertThat(spec.requiredCount).isEqualTo(3)
        assertThat(spec.listName).isEqualTo("Smells Like 1991")
        assertThat(spec.artists).containsExactly("Nirvana", "Pearl Jam", "Soundgarden").inOrder()
        assertThat(spec.artistAllowed("Nirvana")).isTrue()
        assertThat(spec.artistAllowed("Pearl Jam")).isTrue()
        assertThat(spec.artistAllowed("T.M.Revolution")).isFalse()
        assertThat(ListGoalSpec.turnBudget(spec)).isEqualTo(36)
    }

    @Test
    fun `a year or decade after from is not an artist`() {
        val spec = ListGoalSpec.parse("Create a playlist \"Smells Like 1991\" and add 10 songs from 1991.")
        assertThat(spec.requiredCount).isEqualTo(10)
        assertThat(spec.artists).isEmpty()
        assertThat(spec.artistAllowed("Anyone")).isTrue()
        assertThat(ListGoalSpec.parse("add 5 songs from the 90s to \"Mix\"").artists).isEmpty()
        assertThat(ListGoalSpec.turnBudget(spec)).isEqualTo(92)
    }

    @Test
    fun `non counted goals have no spec`() {
        val spec = ListGoalSpec.parse("Open Settings and turn on Wi-Fi")
        assertThat(spec.isCounted).isFalse()
        assertThat(spec.listName).isNull()
        assertThat(spec.artists).isEmpty()
    }
}
