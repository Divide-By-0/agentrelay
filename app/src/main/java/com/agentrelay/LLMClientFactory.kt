package com.agentrelay

object LLMClientFactory {
    fun create(
        apiKey: String,
        model: String,
        onUploadComplete: ((bytes: Int, milliseconds: Long) -> Unit)? = null
    ): LLMClient {
        return when (SecureStorage.providerForModel(model)) {
            SecureStorage.Provider.CLAUDE -> ClaudeAPIClient(apiKey, model, onUploadComplete)
            SecureStorage.Provider.OPENAI -> OpenAIClient(apiKey, model, onUploadComplete)
            SecureStorage.Provider.GEMINI -> GeminiClient(apiKey, model, onUploadComplete)
        }
    }
}
