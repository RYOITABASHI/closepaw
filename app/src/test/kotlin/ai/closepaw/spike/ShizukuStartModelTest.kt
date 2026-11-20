package ai.closepaw.spike

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ShizukuStartModelTest {

    // ── argument parsing ──

    @Test
    fun `connectPort alone is valid and has no pairing`() {
        val (a, err) = ShizukuStartArgs.parse(buildJsonObject { put("connectPort", 37123) })
        assertThat(err).isNull()
        assertThat(a!!.connectPort).isEqualTo(37123)
        assertThat(a.pairPort).isNull()
        assertThat(a.pairCode).isNull()
        assertThat(a.hasPair).isFalse()
    }

    @Test
    fun `pairPort and pairCode together enable pairing, string ports accepted`() {
        val (a, err) = ShizukuStartArgs.parse(buildJsonObject {
            put("connectPort", "40001")
            put("pairPort", 40002)
            put("pairCode", " 123456 ")
        })
        assertThat(err).isNull()
        assertThat(a!!.hasPair).isTrue()
        assertThat(a.connectPort).isEqualTo(40001)
        assertThat(a.pairPort).isEqualTo(40002)
        assertThat(a.pairCode).isEqualTo("123456")
    }

    @Test
    fun `explicit nulls count as absent`() {
        val (a, err) = ShizukuStartArgs.parse(JsonObject(mapOf(
            "connectPort" to JsonPrimitive(5555), "pairPort" to JsonNull, "pairCode" to JsonNull,
        )))
        assertThat(err).isNull()
        assertThat(a!!.hasPair).isFalse()
    }

    @Test
    fun `missing or out-of-range connectPort is rejected`() {
        listOf(
            buildJsonObject {},
            buildJsonObject { put("connectPort", 0) },
            buildJsonObject { put("connectPort", 65536) },
            buildJsonObject { put("connectPort", "abc") },
            buildJsonObject { put("connectPort", 1.5) },
        ).forEach {
            val (a, err) = ShizukuStartArgs.parse(it)
            assertThat(a).isNull()
            assertThat(err).contains("connectPort")
        }
    }

    @Test
    fun `half a pairing is rejected`() {
        assertThat(ShizukuStartArgs.parse(buildJsonObject { put("connectPort", 1); put("pairPort", 2) }).second)
            .contains("together")
        assertThat(ShizukuStartArgs.parse(buildJsonObject { put("connectPort", 1); put("pairCode", "123456") }).second)
            .contains("together")
    }

    @Test
    fun `malformed pair code is rejected without echoing it`() {
        listOf("12345", "1234567", "12a456", "").forEach { code ->
            val (a, err) = ShizukuStartArgs.parse(buildJsonObject {
                put("connectPort", 1); put("pairPort", 2); put("pairCode", code)
            })
            assertThat(a).isNull()
            assertThat(err).contains("pairCode")
            if (code.isNotEmpty()) assertThat(err).doesNotContain(code)
        }
        val (a, err) = ShizukuStartArgs.parse(JsonObject(mapOf(
            "connectPort" to JsonPrimitive(1), "pairPort" to JsonPrimitive(2), "pairCode" to JsonPrimitive(123456),
        )))
        assertThat(a).isNull()
        assertThat(err).contains("pairCode")
    }

    @Test
    fun `toString never reveals the pair code`() {
        val a = ShizukuStartArgs.parse(buildJsonObject {
            put("connectPort", 1); put("pairPort", 2); put("pairCode", "987654")
        }).first!!
        assertThat(a.toString()).doesNotContain("987654")
        assertThat(a.toString()).contains("redacted")
    }

    // ── shell output ──

    @Test
    fun `exit code marker is appended and stripped`() {
        val cmd = ShizukuStartShell.withExitCode(ShizukuStartShell.CHECK_COMMAND)
        assertThat(cmd).isEqualTo("ls ${ShizukuStartShell.STARTER_PATH} 2>&1; echo __closepaw_rc=\$?")
        val r = ShizukuStartShell.parse("${ShizukuStartShell.STARTER_PATH}\n__closepaw_rc=0\n")
        assertThat(r.output).isEqualTo(ShizukuStartShell.STARTER_PATH)
        assertThat(r.exitCode).isEqualTo(0)
        assertThat(ShizukuStartShell.starterExists(r)).isTrue()
    }

    @Test
    fun `missing starter is not found`() {
        val r = ShizukuStartShell.parse(
            "ls: ${ShizukuStartShell.STARTER_PATH}: No such file or directory\n__closepaw_rc=1\n",
        )
        assertThat(r.exitCode).isEqualTo(1)
        assertThat(ShizukuStartShell.starterExists(r)).isFalse()
    }

    @Test
    fun `no marker means unknown exit code and not found`() {
        val r = ShizukuStartShell.parse("${ShizukuStartShell.STARTER_PATH}\n")
        assertThat(r.exitCode).isNull()
        assertThat(r.output).isEqualTo(ShizukuStartShell.STARTER_PATH)
        assertThat(ShizukuStartShell.starterExists(r)).isFalse()
    }

    @Test
    fun `starter output keeps everything before the last marker`() {
        val r = ShizukuStartShell.parse("info: starting\ninfo: shizuku_starter exit with 0\n__closepaw_rc=0")
        assertThat(r.output).isEqualTo("info: starting\ninfo: shizuku_starter exit with 0")
        assertThat(r.exitCode).isEqualTo(0)
    }

    // ── status ──

    @Test
    fun `status exposes running, current and stages`() {
        val recs = listOf(
            SpikeStageRecord("started", 1, mapOf("connectPort" to JsonPrimitive(5555), "hasPair" to JsonPrimitive(false))),
            SpikeStageRecord("connected", 2),
        )
        val s = ShizukuStartStatus.json(recs, running = true)
        assertThat(s["running"]).isEqualTo(JsonPrimitive(true))
        assertThat((s["stages"] as JsonArray).size).isEqualTo(2)
        assertThat(ShizukuStartStatus.currentStage(s)).isEqualTo("connected")
        assertThat(s.toString()).doesNotContain("pairCode")
    }

    @Test
    fun `empty status has null current`() {
        val s = ShizukuStartStatus.json(emptyList(), running = false)
        assertThat(s["current"]).isEqualTo(JsonNull)
        assertThat(ShizukuStartStatus.currentStage(s)).isNull()
    }
}
