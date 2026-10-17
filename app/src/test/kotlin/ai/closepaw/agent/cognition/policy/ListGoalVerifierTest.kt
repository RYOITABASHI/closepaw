package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListGoalVerifierTest {

    private val spec = ListGoalSpec(requiredCount = 3, listName = "Smells Like 1991", artists = listOf("Nirvana", "Pearl Jam", "Soundgarden"))
    private fun row(t: String, a: String) = ListRow(t, a)
    private fun screen(vararg rows: ListRow, rec: Boolean = true) = ParsedListScreen(true, rows.toList(), rec)

    @Test
    fun `not on the playlist page tells the agent to open it`() {
        val v = ListGoalVerifier.decide(spec, listOf(ParsedListScreen(false, emptyList(), false)))
        assertThat(v).isInstanceOf(ListVerification.NotOnTargetScreen::class.java)
        assertThat(v.rejection).contains("\"Smells Like 1991\"")
    }

    @Test
    fun `extraneous artist is reported for removal even when the count is reached`() {
        val v = ListGoalVerifier.decide(spec, listOf(screen(
            row("Lithium", "Nirvana"), row("Jeremy", "Pearl Jam"), row("Rusty Cage - Remastered 2016", "Soundgarden"), row("WILD RUSH", "T.M.Revolution"),
        )))
        val verified = v as ListVerification.Verified
        assertThat(verified.count).isEqualTo(4)
        assertThat(verified.extraneous.map { it.toString() }).containsExactly("WILD RUSH — T.M.Revolution")
        assertThat(v.rejection).contains("Remove these extra items")
        assertThat(v.rejection).contains("WILD RUSH — T.M.Revolution")
    }

    @Test
    fun `too few rows gives the observed count and concrete next steps`() {
        val v = ListGoalVerifier.decide(spec, listOf(screen(row("Lithium", "Nirvana"))))
        assertThat(v.rejection).contains("observed 1 of 3")
        assertThat(v.rejection).contains("Search")
    }

    @Test
    fun `rows across scrolled screens are merged and deduplicated`() {
        val v = ListGoalVerifier.decide(spec, listOf(
            screen(row("Lithium", "Nirvana"), row("Jeremy", "Pearl Jam"), rec = false),
            screen(row("Jeremy", "Pearl Jam"), row("Rusty Cage - Remastered 2016", "Soundgarden"), rec = true),
        ))
        assertThat((v as ListVerification.Verified).count).isEqualTo(3)
        assertThat(v.rejection).isNull()
    }

    @Test
    fun `each requested artist must appear when the count allows`() {
        val v = ListGoalVerifier.decide(spec, listOf(screen(row("Lithium", "Nirvana"), row("Come As You Are", "Nirvana"), row("Jeremy", "Pearl Jam"))))
        assertThat(v.rejection).contains("Soundgarden")
        // Without an artist list, only the count matters.
        val plain = ListGoalSpec(3, "Smells Like 1991", emptyList())
        val ok = ListGoalVerifier.decide(plain, listOf(screen(row("A", "X"), row("B", "Y"), row("C", "Z"))))
        assertThat(ok.rejection).isNull()
    }
}
