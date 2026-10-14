package ai.closepaw.onboarding

/**
 * Validates an API key against the LLM provider endpoint.
 *
 * Uses direct HTTP (not ChatCompletionClient) for exact status code mapping.
 */
interface LlmCredentialValidator {
    sealed interface Result {
        data object Valid : Result
        data class InvalidKey(val message: OnboardingText) : Result
        data class TransientError(val message: OnboardingText) : Result
    }

    suspend fun validate(key: String): Result
}
