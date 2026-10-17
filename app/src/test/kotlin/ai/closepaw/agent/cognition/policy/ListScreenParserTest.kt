package ai.closepaw.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

class ListScreenParserTest {

    private val spec = ListGoalSpec(requiredCount = 3, listName = "Smells Like 1991", artists = listOf("Nirvana", "Pearl Jam", "Soundgarden"))
    private val spotify = "com.spotify.music"

    /** Real `uiautomator dump` of the Spotify playlist page (2026-10-17, one song + recommendations). */
    private fun realPlaylistNodes(): List<ScreenNode> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/spotify_playlist_ui_2026-10-17.xml")) { "fixture missing" }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val out = ArrayList<ScreenNode>()
        val all = doc.getElementsByTagName("node")
        val boundsRe = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
        for (i in 0 until all.length) {
            val n = all.item(i) as Element
            val text = n.getAttribute("text")
            val desc = n.getAttribute("content-desc")
            if (text.isBlank() && desc.isBlank()) continue
            val m = boundsRe.find(n.getAttribute("bounds")) ?: continue
            out.add(ScreenNode(text, desc, top = m.groupValues[2].toInt(), left = m.groupValues[1].toInt(), packageName = n.getAttribute("package")))
        }
        return out
    }

    private fun lines(vararg labels: String, pkg: String = spotify, startTop: Int = 0): List<ScreenNode> =
        labels.mapIndexed { i, l -> ScreenNode(text = l, description = "", top = startTop + i * 100, left = 0, packageName = pkg) }

    @Test
    fun `real Spotify playlist page yields exactly the one real row`() {
        val parsed = ListScreenParser.parse(spec, realPlaylistNodes(), spotify)
        assertThat(parsed.onTargetScreen).isTrue()
        assertThat(parsed.reachedRecommendations).isTrue()
        assertThat(parsed.rows.map { it.toString() }).containsExactly("Spoonman — Soundgarden")
    }

    @Test
    fun `system ui, launcher and recents nodes on the same display are ignored`() {
        val noise = listOf(
            ScreenNode("15:41", "", 0, 0, "com.android.systemui"),
            ScreenNode("", "Battery 42 percent", 0, 500, "com.android.systemui"),
            ScreenNode("", "6 notifications", 0, 700, "com.android.systemui"),
            ScreenNode("Finder", "", 300, 0, "com.sec.android.app.launcher"),
            ScreenNode("Apps", "", 300, 200, "com.sec.android.app.launcher"),
            ScreenNode("Recents", "", 2300, 0, "com.android.systemui"),
            ScreenNode("Screen capture", "", 2300, 400, "com.android.systemui"),
            ScreenNode("Even Flow", "", 1900, 0, "com.sec.android.app.launcher"),
        )
        val parsed = ListScreenParser.parse(spec, realPlaylistNodes() + noise, spotify)
        assertThat(parsed.rows.map { it.toString() }).containsExactly("Spoonman — Soundgarden")

        // Without package info the obvious system noise is still filtered by pattern.
        val anonymous = (realPlaylistNodes() + noise).map { it.copy(packageName = "") }
        val parsedAnon = ListScreenParser.parse(spec, anonymous, spotify)
        assertThat(parsedAnon.rows.map { it.toString() }).containsExactly("Spoonman — Soundgarden")
    }

    @Test
    fun `recommendation rows with Add-to-playlist controls are never counted even above the header`() {
        val nodes = listOf(
            ScreenNode("Smells Like 1991", "", 100, 0, spotify),
            ScreenNode("R1221M", "Created by R1221M", 200, 0, spotify),
            ScreenNode("Even Flow", "", 400, 0, spotify),
            ScreenNode("Explicit", "", 450, 0, spotify),
            ScreenNode("E", "", 450, 60, spotify),
            ScreenNode("Pearl Jam", "", 450, 100, spotify),
            ScreenNode("", "Add Even Flow to playlist", 410, 800, spotify),
            ScreenNode("", "Open context menu for Even Flow", 410, 900, spotify),
            ScreenNode("Spoonman", "", 700, 0, spotify),
            ScreenNode("Soundgarden", "", 750, 0, spotify),
            ScreenNode("", "Open context menu for Spoonman", 710, 900, spotify),
        )
        val parsed = ListScreenParser.parse(spec, nodes, spotify)
        assertThat(parsed.onTargetScreen).isTrue()
        assertThat(parsed.rows.map { it.toString() }).containsExactly("Spoonman — Soundgarden")
    }

    @Test
    fun `the page is the target list only with header evidence`() {
        // Title text alone (e.g. a search result named like the playlist) is not enough.
        val searchHit = lines("Search", "Smells Like 1991", "Playlist", "Lithium", "Nirvana")
        assertThat(ListScreenParser.parse(spec, searchHit, spotify).onTargetScreen).isFalse()
        // A different page of the same app.
        val search = lines("Search", "Nirvana Lithium", "Lithium", "Song • Nirvana", "Add", "Nevermind", "Album • Nirvana")
        assertThat(ListScreenParser.parse(spec, search, spotify).onTargetScreen).isFalse()
        // Title + owner metadata counts, even with no rows yet (empty playlist).
        val empty = listOf(ScreenNode("Smells Like 1991", "", 100, 0, spotify), ScreenNode("", "Created by R1221M", 200, 0, spotify), ScreenNode("", "Add to this playlist", 300, 0, spotify))
        val parsedEmpty = ListScreenParser.parse(spec, empty, spotify)
        assertThat(parsedEmpty.onTargetScreen).isTrue()
        assertThat(parsedEmpty.rows).isEmpty()
    }

    @Test
    fun `generic fallback pairs title and artist lines for apps without row anchors`() {
        val other = ListGoalSpec(3, "Road Trip", emptyList())
        val screen = lines(
            "Navigate up", "Road Trip", "4 songs • 15 min", "Shuffle", "Play",
            "Lithium", "Nirvana", "Jeremy", "Pearl Jam", "Rusty Cage - Remastered 2016", "Soundgarden",
            "WILD RUSH", "Video • ", "T.M.Revolution", "Recommended Songs", "Black Hole Sun", "Soundgarden",
            pkg = "com.example.music",
        )
        val parsed = ListScreenParser.parse(other, screen, "com.example.music")
        assertThat(parsed.onTargetScreen).isTrue()
        assertThat(parsed.reachedRecommendations).isTrue()
        assertThat(parsed.rows.map { it.toString() }).containsExactly(
            "Lithium — Nirvana", "Jeremy — Pearl Jam", "Rusty Cage - Remastered 2016 — Soundgarden", "WILD RUSH — T.M.Revolution",
        ).inOrder()
    }
}
