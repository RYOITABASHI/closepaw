package ai.closepaw.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectiveMaxCompletionTokensTest {

    @Test
    fun `explicit requested limit wins`() {
        assertEquals(32_768L, effectiveMaxCompletionTokens("https://openrouter.ai/api/v1", 32_768L))
        assertEquals(512L, effectiveMaxCompletionTokens("https://api.openai.com/v1", 512L))
        assertEquals(512L, effectiveMaxCompletionTokens(null, 512L))
    }

    @Test
    fun `OpenRouter gets a default limit`() {
        assertEquals(16_384L, effectiveMaxCompletionTokens("https://openrouter.ai/api/v1", null))
    }

    @Test
    fun `OpenRouter host match ignores case`() {
        assertEquals(16_384L, effectiveMaxCompletionTokens("HTTPS://OpenRouter.AI/api/v1", null))
    }

    @Test
    fun `other hosts have no default limit`() {
        assertNull(effectiveMaxCompletionTokens("https://api.openai.com/v1", null))
        assertNull(effectiveMaxCompletionTokens("https://openrouter.ai.example.com/v1", null))
    }

    @Test
    fun `missing or invalid base URL has no default limit`() {
        assertNull(effectiveMaxCompletionTokens(null, null))
        assertNull(effectiveMaxCompletionTokens("https://[invalid", null))
        assertNull(effectiveMaxCompletionTokens("openrouter.ai", null))
    }

    @Test
    fun `scheme-relative OpenRouter URL has no default limit`() {
        assertNull(effectiveMaxCompletionTokens("//openrouter.ai/api/v1", null))
    }

    @Test
    fun `OpenRouter URL with an explicit port gets a default limit`() {
        assertEquals(16_384L, effectiveMaxCompletionTokens("https://openrouter.ai:443/api/v1", null))
    }

    @Test
    fun `OpenRouter in userinfo has no default limit`() {
        assertNull(effectiveMaxCompletionTokens("https://openrouter.ai@evil.com/", null))
    }

    @Test
    fun `empty base URL has no default limit`() {
        assertNull(effectiveMaxCompletionTokens("", null))
    }

    @Test
    fun `OpenRouter host with a trailing dot has no default limit`() {
        assertNull(effectiveMaxCompletionTokens("https://openrouter.ai./api/v1", null))
    }

    @Test
    fun `explicit zero and negative limits are forwarded unchanged`() {
        assertEquals(0L, effectiveMaxCompletionTokens("https://openrouter.ai/api/v1", 0L))
        assertEquals(-1L, effectiveMaxCompletionTokens("https://openrouter.ai/api/v1", -1L))
    }
}
