package ai.closepaw.mcp

import ai.closepaw.mcp.McpSessionArgs.CorrelationPair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class McpCorrelationPairTest {
    @Test
    fun absentPairIsValid() {
        assertPair(CorrelationPair.ABSENT, "{}")
    }

    @Test
    fun wellFormedPairIsValidAtBothTimestampLengthBounds() {
        assertPair(CorrelationPair.WELL_FORMED, """{"reqId":"$reqId","nonce":"$nonce"}""")
        assertPair(CorrelationPair.WELL_FORMED, """{"reqId":"1234567890123456-0123abcd","nonce":"$nonce"}""")
    }

    @Test
    fun invalidShapesAreMalformed() {
        val badReqIds = listOf("123456789-0123abcd", "12345678901234567-0123abcd",
            "1700000000/0123abcd", "1700000000-0123abcg", reqId.uppercase(), "")
        for (badReqId in badReqIds) {
            assertPair(CorrelationPair.MALFORMED, """{"reqId":"$badReqId","nonce":"$nonce"}""")
        }
        val badNonces = listOf(nonce.dropLast(1), nonce + "0", "g" + nonce.drop(1), nonce.uppercase(), "")
        for (badNonce in badNonces) {
            assertPair(CorrelationPair.MALFORMED, """{"reqId":"$reqId","nonce":"$badNonce"}""")
        }
    }

    @Test
    fun eitherKeyAloneIsMalformedEvenWhenItsValueIsWellFormed() {
        for ((key, valid) in listOf("reqId" to reqId, "nonce" to nonce)) {
            for (value in listOf("\"$valid\"", "\"\"", "null", "false")) {
                assertPair(CorrelationPair.MALFORMED, """{"$key":$value}""")
            }
        }
    }

    @Test
    fun nonStringJsonValuesAreMalformed() {
        for (value in listOf("1234567890", "true", """{"value":"$reqId"}""", "[]", "null")) {
            assertPair(CorrelationPair.MALFORMED, """{"reqId":$value,"nonce":"$nonce"}""")
            assertPair(CorrelationPair.MALFORMED, """{"reqId":"$reqId","nonce":$value}""")
        }
    }

    @Test
    fun overLengthValuesAreMalformed() {
        val overLength = "a".repeat(129)
        assertPair(CorrelationPair.MALFORMED, """{"reqId":"$overLength","nonce":"$nonce"}""")
        assertPair(CorrelationPair.MALFORMED, """{"reqId":"$reqId","nonce":"$overLength"}""")
    }

    private fun assertPair(expected: CorrelationPair, pairJson: String) {
        val pair = Json.parseToJsonElement(pairJson).jsonObject
        val args = JsonObject(pair + ("goal" to JsonPrimitive("Open notes")))
        assertEquals(pairJson, expected, McpSessionArgs.classifyCorrelationPair(args))
        val error = McpSessionArgs.validate(McpHandler.TOOL_START_SESSION, args)
        if (expected == CorrelationPair.MALFORMED) {
            assertNotNull(pairJson, error)
        } else {
            assertNull(pairJson, error)
        }
    }

    private val reqId = "1700000000-0123abcd"
    private val nonce = "0123456789abcdef0123456789abcdef"
}
