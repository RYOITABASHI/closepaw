package ai.closepaw.app

import ai.closepaw.BuildConfig
import ai.closepaw.agent.AgentModelResolver
import ai.closepaw.auth.AuthStore
import ai.closepaw.auth.MissingCredential
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMClientFactory
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.protocol.LLMBackendType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RunGoalBrainAdmissionTest {
    @Test
    fun refusesOpenRouterUnderAnyCatalogAlias() {
        for (alias in listOf("glm-5", "repointed")) {
            val decision = admitRunGoalBrain(
                LLMBackendType.OPENAI, alias, catalog(alias, LLMProvider.OPENROUTER),
            ) { error("Cloud OpenRouter must be refused before reading auth") }
            val refusal = (decision as RunGoalBrainAdmission.Refused).result
            assertEquals("legacy_brain_unavailable", refusal.code)
            assertTrue(refusal.message.contains("OpenRouter"))
            assertTrue(refusal.message.contains("Shelly"))
            assertTrue(refusal.message.contains("start_session"))
            assertTrue(refusal.message.contains("Codex"))
        }
    }

    @Test
    fun admitsSameAliasRepointedToCodex() {
        val catalog = catalog("glm-5", LLMProvider.OPENAI_CODEX)
        assertSame(catalog, admittedCatalog(admitRunGoalBrain(LLMBackendType.OPENAI, "glm-5", catalog) {
            error("Codex must bypass the legacy client check")
        }))
    }

    @Test
    fun admitsLocalSelection() {
        val catalog = catalog("local", LLMProvider.LOCAL_LFM)
        assertSame(catalog, admittedCatalog(admitRunGoalBrain(LLMBackendType.LOCAL, "local", catalog) {
            error("Local selection must bypass the legacy client check")
        }))
    }

    @Test
    fun localFallbackStaysPinnedAfterKeyAppears() {
        val alias = "glm-5"
        val catalog = catalog(alias, LLMProvider.OPENROUTER)
        val store = mockk<AuthStore>(relaxed = true)
        every { store.requireApiKey(LLMProvider.OPENROUTER) } throws MissingCredential(LLMProvider.OPENROUTER)

        val snapshot = admittedCatalog(admitRunGoalBrain(LLMBackendType.LOCAL, alias, catalog) { store })
        every { store.requireApiKey(LLMProvider.OPENROUTER) } returns "test-key"

        val sessionClient = mockk<LLMClient>()
        val originalFactory = LLMClientFactory(catalog, store)
        val originalResolution = AgentModelResolver(sessionClient, catalog, originalFactory).resolve(alias)
        assertTrue(originalResolution.llmClient !== sessionClient)

        val admittedFactory = LLMClientFactory(snapshot, store)
        val resolution = AgentModelResolver(sessionClient, snapshot, admittedFactory).resolve(alias)
        assertSame(sessionClient, resolution.llmClient)
        assertEquals(alias, resolution.modelId)
        assertFalse(resolution.supportsVision)

        val newRun = admitRunGoalBrain(LLMBackendType.LOCAL, alias, catalog) { store }
        assertEquals("legacy_brain_unavailable", (newRun as RunGoalBrainAdmission.Refused).result.code)
    }

    @Test
    fun localAdmissionFollowsBuildSpecificHttpClientValidation() {
        val alias = "glm-5"
        val catalog = catalog(alias, LLMProvider.OPENROUTER, "http://openrouter.ai/api/v1")
        val store = mockk<AuthStore>(relaxed = true)
        every { store.requireApiKey(LLMProvider.OPENROUTER) } returns "test-key"

        val decision = admitRunGoalBrain(LLMBackendType.LOCAL, alias, catalog) { store }

        if (BuildConfig.DEBUG) {
            assertEquals("legacy_brain_unavailable", (decision as RunGoalBrainAdmission.Refused).result.code)
        } else {
            val snapshot = admittedCatalog(decision)
            val sessionClient = mockk<LLMClient>()
            val resolution = AgentModelResolver(
                sessionClient, snapshot, LLMClientFactory(snapshot, store),
            ).resolve(alias)
            assertSame(sessionClient, resolution.llmClient)
        }
    }

    private fun admittedCatalog(decision: RunGoalBrainAdmission): ModelCatalog =
        (decision as RunGoalBrainAdmission.Admitted).catalog

    private fun catalog(alias: String, provider: LLMProvider, baseUrl: String? = null): ModelCatalog {
        val urlField = baseUrl?.let { """, "base_url":"$it"""" }.orEmpty()
        return ModelCatalog.fromJson(
            """{"$alias":{"display_name":"Fixture","provider":"${provider.name}","api":"chat","model_id":"fixture"$urlField}}""",
        )
    }
}
