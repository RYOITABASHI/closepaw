package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListScreenParserTest {

    private val spec = ListGoalSpec(requiredCount = 3, listName = "Smells Like 1991", artists = listOf("Nirvana", "Pearl Jam", "Soundgarden"))

    /** Spotify playlist page as seen on-device (2026-10-17), including the recommendation section. */
    private val playlistScreen = listOf(
        "Navigate up", "Smells Like 1991", "RYO", "4 songs • 15 min", "Add to this playlist", "Download",
        "More options", "Shuffle", "Play",
        "Lithium", "Nirvana", "More options for Lithium",
        "Jeremy", "Pearl Jam", "More options for Jeremy",
        "Rusty Cage - Remastered 2016", "Soundgarden", "More options for Rusty Cage",
        "WILD RUSH", "Video • ", "T.M.Revolution", "More options for WILD RUSH",
        "Recommended Songs", "Based on the songs in this playlist",
        "Black Hole Sun", "Soundgarden", "Add", "Alive", "Pearl Jam", "Add", "Refresh",
        "Home", "Search", "Your Library", "Create",
    )

    @Test
    fun `reads title and artist rows and stops at the recommendation section`() {
        val parsed = ListScreenParser.parse(spec, playlistScreen)
        assertThat(parsed.onTargetScreen).isTrue()
        assertThat(parsed.reachedRecommendations).isTrue()
        assertThat(parsed.rows.map { it.toString() }).containsExactly(
            "Lithium — Nirvana",
            "Jeremy — Pearl Jam",
            "Rusty Cage - Remastered 2016 — Soundgarden",
            "WILD RUSH — T.M.Revolution",
        ).inOrder()
    }

    @Test
    fun `another screen is not the target list`() {
        val search = listOf("Search", "Nirvana Lithium", "Lithium", "Song • Nirvana", "Add", "Nevermind", "Album • Nirvana")
        val parsed = ListScreenParser.parse(spec, search)
        assertThat(parsed.onTargetScreen).isFalse()
        assertThat(parsed.rows).isEmpty()
    }

    @Test
    fun `a scrolled page without the recommendation header keeps reading rows`() {
        val page2 = listOf("Smells Like 1991", "Rusty Cage - Remastered 2016", "Soundgarden", "WILD RUSH", "Video • ", "T.M.Revolution", "Come As You Are", "Nirvana")
        val parsed = ListScreenParser.parse(spec, page2)
        assertThat(parsed.onTargetScreen).isTrue()
        assertThat(parsed.reachedRecommendations).isFalse()
        assertThat(parsed.rows).hasSize(3)
        assertThat(parsed.rows.last().toString()).isEqualTo("Come As You Are — Nirvana")
    }
}
