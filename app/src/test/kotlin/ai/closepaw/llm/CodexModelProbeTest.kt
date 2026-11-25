package ai.closepaw.llm

import ai.closepaw.auth.CodexHeaders
import ai.closepaw.auth.MissingCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CodexModelProbeTest {
    @Test
    fun reportsReturnedModelsIncludingHiddenOnesAndIgnoresUnknownFields() = runBlocking {
        val outcomes = mutableListOf<String>()
        val probe = CodexModelProbe(
            clientVersion = "0.1.0+test",
            headers = { CodexHeaders("test-access-token", "test-account-id", null) },
            exchange = { url, headers ->
                assertEquals("https://chatgpt.com/backend-api/codex/models?client_version=0.1.0%2Btest", url.toString())
                assertEquals(
                    mapOf(
                        "Authorization" to "Bearer test-access-token",
                        "ChatGPT-Account-Id" to "test-account-id",
                        "originator" to "pi",
                        "Accept" to "application/json",
                    ),
                    headers,
                )
                CodexModelProbe.HttpResult(200, """
                    {"extra":true,"models":[
                      {"slug":"alpha","visibility":"hide","display_name":"Alpha","description":"Example","unknown":{}},
                      {"slug":"beta","visibility":false,"display_name":"Echoed test-account-id"},
                      {"slug":12},
                      {"ignored":true},
                      null
                    ]}
                """.trimIndent())
            },
            reportOutcome = outcomes::add,
        )

        val result = probe.check() as CodexModelProbe.Result.Success
        assertEquals(listOf("alpha", "beta"), result.models.map { it.slug })
        assertEquals("hide", result.models[0].visibility)
        assertEquals("Alpha", result.models[0].displayName)
        assertEquals("Example", result.models[0].description)
        assertEquals("false", result.models[1].visibility)
        assertEquals("Echoed [redacted]", result.models[1].displayName)
        assertNull(result.models[1].description)
        assertEquals(listOf("alpha\nbeta"), outcomes)
    }

    @Test
    fun emptyArrayIsSuccessfulAndAccountHeaderIsOptional() = runBlocking {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { CodexHeaders("test-access-token", null, null) },
            exchange = { _, headers ->
                assertTrue("ChatGPT-Account-Id" !in headers)
                CodexModelProbe.HttpResult(200, """{"models":[]}""")
            },
        )
        assertEquals(CodexModelProbe.Result.Success(emptyList()), probe.check())
    }

    @Test
    fun structuredErrorReportsStatusOnly() = runBlocking {
        val outcomes = mutableListOf<String>()
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { CodexHeaders("test-access-token", "test-account-id", null) },
            exchange = { _, _ ->
                CodexModelProbe.HttpResult(403, """{"error":{"message":"denied ${"x".repeat(600)}"},"other":"test-refresh-token"}""")
            },
            storedSecrets = { listOf("test-refresh-token") },
            reportOutcome = outcomes::add,
        )
        val result = probe.check() as CodexModelProbe.Result.Failure
        assertEquals(CodexModelProbe.Result.Failure(403, ""), result)
        assertEquals("HTTP 403", result.diagnostic())
        assertEquals(listOf("HTTP 403"), outcomes)
    }

    @Test
    fun missingCredentialSkipsExchange() = runBlocking {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { throw MissingCredential(LLMProvider.OPENAI_CODEX) },
            exchange = { _, _ -> error("Network should not be called") },
        )
        assertEquals(CodexModelProbe.Result.Failure(null, "No credential for provider OPENAI_CODEX"), probe.check())
    }

    @Test
    fun networkExceptionReturnsClassOnly() = runBlocking {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { CodexHeaders("test-access-token", null, null) },
            exchange = { _, _ -> throw IOException("Request failed with test-access-token") },
        )
        assertEquals(CodexModelProbe.Result.Failure(null, "IOException"), probe.check())
    }

    @Test
    fun failureBeforeCredentialReadReturnsClassOnly() = runBlocking {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { error("Headers should not be read") },
            exchange = { _, _ -> error("Network should not be called") },
            storedSecrets = { throw IOException("Unread credential material") },
        )
        assertEquals(CodexModelProbe.Result.Failure(null, "IOException"), probe.check())
    }

    @Test
    fun unstructuredErrorReportsStatusOnly() = runBlocking {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { CodexHeaders("test-access-token", null, null) },
            exchange = { _, _ -> CodexModelProbe.HttpResult(403, "Unstructured error") },
        )
        assertEquals(CodexModelProbe.Result.Failure(403, ""), probe.check())
    }

    @Test
    fun malformedResponseDoesNotExposePayloadInParseError() = runBlocking {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { CodexHeaders("test-access-token", null, null) },
            exchange = { _, _ -> CodexModelProbe.HttpResult(200, """{"oops":"test-access-token"}""") },
        )
        assertEquals(CodexModelProbe.Result.Failure(200, "Invalid Codex models response"), probe.check())
    }

    @Test
    fun credentialSpellingsInErrorReportStatusOnly() = runBlocking {
        val bodies = listOf(
            """{"error":"Denied test-access-token"}""",
            """{"error":"Denied test\u002daccount\u002did"}""",
            """{"error":{"message":"Denied test- access-token for test- account-id"}}""",
        )
        for (body in bodies) {
            val outcomes = mutableListOf<String>()
            val probe = CodexModelProbe(
                clientVersion = "0.1.0",
                headers = { CodexHeaders("test-access-token", "test-account-id", null) },
                exchange = { _, _ -> CodexModelProbe.HttpResult(403, body) },
                reportOutcome = outcomes::add,
            )

            val result = probe.check() as CodexModelProbe.Result.Failure
            assertEquals(body, "HTTP 403", result.diagnostic())
            assertEquals(CodexModelProbe.Result.Failure(403, ""), result)
            assertEquals(listOf("HTTP 403"), outcomes)
        }
    }

    @Test
    fun entirelyInvalidNonemptyArrayIsNotAnEmptyEntitlementList() = runBlocking {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { CodexHeaders("test-access-token", null, null) },
            exchange = { _, _ -> CodexModelProbe.HttpResult(200, """{"models":[{"slug":12}]}""") },
        )
        assertEquals(CodexModelProbe.Result.Failure(200, "Invalid Codex models response"), probe.check())
    }

    @Test
    fun cancellationPropagates() {
        val probe = CodexModelProbe(
            clientVersion = "0.1.0",
            headers = { CodexHeaders("test-access-token", null, null) },
            exchange = { _, _ -> throw CancellationException() },
        )
        assertThrows(CancellationException::class.java) { runBlocking { probe.check() } }
    }
}
