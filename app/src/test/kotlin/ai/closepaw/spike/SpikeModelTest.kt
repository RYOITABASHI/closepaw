package ai.closepaw.spike

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class SpikeModelTest {

    // ── redirect host allowlist ──

    @Test
    fun `redirect policy allows github hosts over https`() {
        assertThat(SpikeRedirectPolicy.isAllowed("https", "github.com")).isTrue()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "objects.githubusercontent.com")).isTrue()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "release-assets.githubusercontent.com")).isTrue()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "Foo.GitHubUserContent.com.")).isTrue()
    }

    @Test
    fun `redirect policy rejects other hosts, lookalikes, and non-https`() {
        assertThat(SpikeRedirectPolicy.isAllowed("https", "evil.com")).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "github.com.evil.com")).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "evilgithub.com")).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "evilgithubusercontent.com")).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "githubusercontent.com")).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "www.github.com")).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("http", "github.com")).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("https", null)).isFalse()
        assertThat(SpikeRedirectPolicy.isAllowed("https", "")).isFalse()
    }

    // ── versionName -> asset URL ──

    @Test
    fun `shelly CI version maps to its own release asset`() {
        val a = SpikeAsset.fromVersionName("0.1.0-shelly.62")!!
        assertThat(a.runNumber).isEqualTo(62)
        assertThat(a.tag).isEqualTo("shelly-v0.1.0-62")
        assertThat(a.fileName).isEqualTo("ClosePaw-shelly-62.apk")
        assertThat(a.url).isEqualTo(
            "https://github.com/RYOITABASHI/closepaw/releases/download/shelly-v0.1.0-62/ClosePaw-shelly-62.apk",
        )
    }

    @Test
    fun `multi-digit run numbers and surrounding whitespace`() {
        assertThat(SpikeAsset.fromVersionName(" 0.1.0-shelly.1234 ")!!.url)
            .endsWith("/shelly-v0.1.0-1234/ClosePaw-shelly-1234.apk")
    }

    @Test
    fun `non-shelly or malformed versions yield null`() {
        listOf(
            null, "", "0.1.0", "0.1.0-debug", "0.1.0-shelly.", "0.1.0-shelly.abc", "0.1.0-shelly.0",
            "0.1.0-shelly.62-dirty", "x0.1.0-shelly.62", "0.1.0-shelly.62/../../evil",
            "0.1.0-shelly.99999999999999999999",
        ).forEach { assertThat(SpikeAsset.fromVersionName(it)).isNull() }
    }

    // ── stage records ──

    @Test
    fun `record serializes flat with stage and atMs first`() {
        val rec = SpikeStageRecord(
            "downloaded", 1_700_000_000_000,
            mapOf("bytes" to JsonPrimitive(42L), "sha256" to JsonPrimitive("ab")),
        )
        assertThat(rec.toJson().toString())
            .isEqualTo("""{"stage":"downloaded","atMs":1700000000000,"bytes":42,"sha256":"ab"}""")
    }

    @Test
    fun `fields cannot overwrite stage or atMs`() {
        val rec = SpikeStageRecord("started", 5, mapOf("stage" to JsonPrimitive("evil"), "atMs" to JsonPrimitive(1)))
        val json = rec.toJson()
        assertThat(json["stage"]!!.jsonPrimitive.content).isEqualTo("started")
        assertThat(json["atMs"]!!.jsonPrimitive.long).isEqualTo(5)
    }

    @Test
    fun `log round-trips and appends`() {
        val a = SpikeStageRecord("started", 1)
        val b = SpikeStageRecord("session-created", 2, mapOf("sessionId" to JsonPrimitive(7)))
        val text = SpikeStageLog.append(SpikeStageLog.encode(listOf(a)), b)
        assertThat(SpikeStageLog.decode(text)).containsExactly(a, b).inOrder()
    }

    @Test
    fun `decode tolerates garbage and skips malformed entries`() {
        assertThat(SpikeStageLog.decode(null)).isEmpty()
        assertThat(SpikeStageLog.decode("not json")).isEmpty()
        assertThat(SpikeStageLog.decode("""{"stage":"x"}""")).isEmpty()
        val mixed = """[{"stage":"started","atMs":1},{"atMs":2},{"stage":"x"},7,{"stage":"written","atMs":3,"bytes":9}]"""
        assertThat(SpikeStageLog.decode(mixed).map { it.stage }).containsExactly("started", "written").inOrder()
    }

    @Test
    fun `log is capped to the newest records`() {
        val many = (1..(SpikeStageLog.MAX_RECORDS + 10)).map { SpikeStageRecord("s$it", it.toLong()) }
        val decoded = SpikeStageLog.decode(SpikeStageLog.encode(many))
        assertThat(decoded).hasSize(SpikeStageLog.MAX_RECORDS)
        assertThat(decoded.last().stage).isEqualTo("s${SpikeStageLog.MAX_RECORDS + 10}")
    }

    @Test
    fun `last completed stage ignores the failed record`() {
        val recs = listOf(SpikeStageRecord("started", 1), SpikeStageRecord("downloaded", 2), SpikeStageRecord("failed", 3))
        assertThat(SpikeStageLog.lastCompletedStage(recs)).isEqualTo("downloaded")
        assertThat(SpikeStageLog.lastCompletedStage(emptyList())).isEqualTo("none")
    }

    @Test
    fun `describe unwraps reflection and caps length`() {
        val inner = SecurityException("Permission Denial: x")
        val wrapped = java.lang.reflect.InvocationTargetException(inner)
        assertThat(SpikeStageLog.describe(wrapped)).isEqualTo("java.lang.SecurityException: Permission Denial: x")
        assertThat(SpikeStageLog.describe(IllegalStateException("y".repeat(5000))).length)
            .isEqualTo(SpikeStageLog.MAX_FIELD_CHARS)
    }

    @Test
    fun `status json carries current stage, history and lastUpdateTime`() {
        val recs = listOf(SpikeStageRecord("started", 1), SpikeStageRecord("commit-sent", 2))
        val s = SpikeStageLog.statusJson(recs, 123L, running = true)
        assertThat((s["current"] as JsonObject)["stage"]!!.jsonPrimitive.content).isEqualTo("commit-sent")
        assertThat((s["stages"] as JsonArray)).hasSize(2)
        assertThat(s["packageLastUpdateTime"]!!.jsonPrimitive.long).isEqualTo(123L)
        assertThat(s["running"]!!.jsonPrimitive.content).isEqualTo("true")

        val empty = SpikeStageLog.statusJson(emptyList(), null, running = false)
        assertThat(empty["current"]).isEqualTo(JsonNull)
        assertThat(empty["packageLastUpdateTime"]).isEqualTo(JsonNull)
    }
}
