package com.agentrelay

import android.util.Log
import com.agentrelay.models.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// ── Gemini request/response models ──────────────────────────────────────────

private data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerializedName("systemInstruction")
    val systemInstruction: GeminiContent? = null,
    @SerializedName("generationConfig")
    val generationConfig: GeminiGenerationConfig? = null
)

private data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

private data class GeminiPart(
    val text: String? = null,
    @SerializedName("inlineData")
    val inlineData: GeminiInlineData? = null
)

private data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

private data class GeminiGenerationConfig(
    @SerializedName("maxOutputTokens")
    val maxOutputTokens: Int = 4096,
    @SerializedName("responseMimeType")
    val responseMimeType: String? = null
)

private data class GeminiResponse(
    val candidates: List<GeminiCandidate>?,
    @SerializedName("promptFeedback")
    val promptFeedback: GeminiPromptFeedback?,
    val error: GeminiError?
)

private data class GeminiCandidate(
    val content: GeminiContent?,
    @SerializedName("finishReason")
    val finishReason: String?
)

private data class GeminiError(
    val code: Int?,
    val message: String?,
    val status: String?
)

private data class GeminiPromptFeedback(
    @SerializedName("blockReason")
    val blockReason: String?,
    @SerializedName("blockReasonMessage")
    val blockReasonMessage: String?
)

// ── Retrofit interface ──────────────────────────────────────────────────────

private interface GeminiAPI {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>
}

// ── Client ──────────────────────────────────────────────────────────────────

class GeminiClient(
    apiKey: String,
    model: String,
    onUploadComplete: ((bytes: Int, milliseconds: Long) -> Unit)? = null
) : LLMClient(apiKey, model, onUploadComplete) {

    private val gson: Gson = GsonBuilder().create()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val api = retrofit.create(GeminiAPI::class.java)

    override suspend fun sendRaw(
        messages: List<Message>,
        systemPrompt: String
    ): Result<String> {
        return try {
            val contents = mutableListOf<GeminiContent>()

            // Convert conversation messages
            for (msg in messages) {
                val parts = mutableListOf<GeminiPart>()
                for (block in msg.content) {
                    when (block) {
                        is ContentBlock.TextContent -> {
                            parts.add(GeminiPart(text = block.text))
                        }
                        is ContentBlock.ImageContent -> {
                            parts.add(GeminiPart(
                                inlineData = GeminiInlineData(
                                    mimeType = block.source.mediaType,
                                    data = block.source.data
                                )
                            ))
                        }
                    }
                }
                // Gemini uses "model" instead of "assistant"
                val role = if (msg.role == "assistant") "model" else msg.role
                contents.add(GeminiContent(role = role, parts = parts))
            }

            // System prompt as systemInstruction
            val systemInstruction = GeminiContent(
                parts = listOf(GeminiPart(text = systemPrompt))
            )

            val request = GeminiRequest(
                contents = contents,
                systemInstruction = systemInstruction,
                generationConfig = GeminiGenerationConfig(
                    maxOutputTokens = 4096,
                    responseMimeType = "application/json"
                )
            )

            val startTime = System.currentTimeMillis()
            var activeModel = normalizeModel(model)
            var response = api.generateContent(model = activeModel, apiKey = apiKey, request = request)

            // Some configured aliases may not be accepted by the Gemini API; retry with known stable fallback.
            if (!response.isSuccessful && activeModel != FALLBACK_FLASH_MODEL && shouldFallbackModel(response.code())) {
                Log.w(TAG, "Gemini model '$activeModel' failed (${response.code()}); retrying with '$FALLBACK_FLASH_MODEL'")
                activeModel = FALLBACK_FLASH_MODEL
                response = api.generateContent(model = activeModel, apiKey = apiKey, request = request)
            }
            val endTime = System.currentTimeMillis()

            // Report upload stats
            val estimatedBytes = messages.sumOf { message ->
                message.content.sumOf { content ->
                    when (content) {
                        is ContentBlock.ImageContent -> content.source.data.length / 4 * 3
                        is ContentBlock.TextContent -> content.text.length
                        else -> 0
                    }
                }
            }
            onUploadComplete?.invoke(estimatedBytes, endTime - startTime)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error != null) {
                    Result.failure(Exception("Gemini API Error: ${body.error.message}"))
                } else {
                    val text = extractCandidateText(body.candidates)
                    if (text.isNullOrBlank()) {
                        val blockMsg = body.promptFeedback?.blockReasonMessage
                            ?: body.promptFeedback?.blockReason
                            ?: body.candidates?.firstOrNull()?.finishReason
                            ?: "No content in Gemini response"
                        throw Exception("No parseable Gemini text: $blockMsg")
                    }
                    Result.success(text)
                }
            } else {
                Result.failure(Exception("Gemini API Error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API request failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "GeminiClient"
        private const val FALLBACK_FLASH_MODEL = "gemini-2.5-flash"

        @androidx.annotation.VisibleForTesting
        internal fun normalizeModel(model: String): String {
            return when (model) {
                "gemini-3.0-flash" -> FALLBACK_FLASH_MODEL
                else -> model
            }
        }

        private fun extractCandidateText(candidates: List<GeminiCandidate>?): String? {
            if (candidates.isNullOrEmpty()) return null
            val combined = candidates
                .asSequence()
                .flatMap { candidate -> candidate.content?.parts?.asSequence() ?: emptySequence() }
                .mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }
                .joinToString("\n")
            return combined.takeIf { it.isNotBlank() }
        }

        private fun shouldFallbackModel(code: Int): Boolean {
            return code == 400 || code == 404
        }
    }
}
